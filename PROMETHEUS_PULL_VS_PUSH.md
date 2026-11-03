# BẢN PHÂN TÍCH VÀ PHẢN BIỆN KIẾN TRÚC: PULL (PROMETHEUS) VS PUSH (DATADOG, TELEGRAF, AWS CLOUDWATCH)

---

## 1. Tình huống phản biện
Trong cuộc họp kiến trúc, chuyên gia đặt câu hỏi:
> *"Tại sao chúng ta lại dùng Prometheus? Prometheus đi gõ cửa (Pull) từng Service để xin dữ liệu. Nếu chúng ta có 1000 Microservices liên tục bật tắt (Auto-scaling), thì Prometheus làm sao biết ai sinh ra mà gõ cửa? Sao không để các service tự chủ động gửi (Push) dữ liệu lên một Server Trung tâm tập trung?"*

Dưới đây là bản phân tích toàn diện, bẻ gãy các ngộ nhận và bảo vệ quyết định sử dụng kiến trúc Pull-based của Prometheus.

---

## 2. Vì sao mô hình Pull giúp Backend ít bị áp lực và an toàn hơn Push?

### 2.1. Kiểm soát tải và tốc độ cào dữ liệu (Flow / Rate Control)
* **Trong mô hình Push:** 
  * 1000 instances cùng đẩy metrics liên tục. Khi hệ thống chịu tải đột biến (flash sale, campaign), số lượng request tăng vọt khiến số lượng metric cần push cũng tăng đột biến.
  * Hiện tượng **Thundering Herd** xảy ra: Hàng nghìn service cùng lúc "dội bom" request vào máy chủ Monitoring, dễ làm máy chủ này quá tải hoặc sập.
* **Trong mô hình Pull (Prometheus):**
  * **Prometheus nắm quyền chủ động tuyệt đối về tốc độ thu thập (`scrape_interval`).** Dù ứng dụng đang xử lý 10 req/s hay 100,000 req/s, Prometheus vẫn chỉ ghé thăm đúng chu kỳ cấu hình (ví dụ 15 giây/lần).
  * Backend không hề chịu bất kỳ áp lực về băng thông mạng hay chi phí I/O để gửi metrics đi.

### 2.2. Tránh "Chết chùm" (Cascading Failures)
* **Trong mô hình Push:**
  * Nếu Monitoring Server bị chậm, nghẽn mạng hoặc chết, các Service Backend phải lưu trữ metrics trong memory buffer và thực hiện cơ chế Retry.
  * Khi buffer bị đầy hoặc connection pool bị nghẽn do chờ timeout từ Monitoring Server, **chính ứng dụng Backend sẽ bị tràn RAM (OOM) hoặc nghẽn luồng xử lý và sập theo**. Một hệ thống giám sát phụ trợ lại trở thành nguyên nhân đánh sập hệ sinh thái lõi của doanh nghiệp.
* **Trong mô hình Pull:**
  * Backend hoàn toàn thụ động: App chỉ việc tăng các biến counter trong RAM nội bộ (in-memory).
  * **Nếu Prometheus chết:** Ứng dụng Backend hoàn toàn không bị ảnh hưởng, không retry, không buffer, không tốn thêm một byte RAM hay một socket kết nối nào.

### 2.3. Phát hiện trạng thái sống/chết (Out-of-the-box Liveness Detection)
* **Mô hình Push:** Khi không nhận được metric từ một service, hệ thống rất khó phân biệt: Service đó đã chết? Hay đường truyền mạng bị chậm? Hay bản thân service đang bận xử lý logic nên chưa kịp push?
* **Mô hình Pull:** Nếu Prometheus gửi HTTP request tới target mà nhận lỗi `Connection Refused` hoặc `Timeout`, nó lập tức xác định chính xác target đó bị **DOWN** ngay tại chu kỳ cào dữ liệu đó thông qua metric hệ thống `up == 0`.

---

## 3. Lời giải cho bài toán Auto-scaling: Làm thế nào Prometheus biết 1000 service mới?

Ngộ nhận lớn nhất của người phản đối là: *"Pull bắt buộc phải cấu hình IP tĩnh thủ công từng con số"*. Trên thực tế, sức mạnh vượt trội của Prometheus gắn liền với **Service Discovery (SD)**:

Prometheus được thiết kế từ gốc rễ cho môi trường Cloud-Native và Kubernetes. Prometheus không tự mò tìm IP mà **tích hợp sẵn các cơ chế Service Discovery tự động**:

1. **Kubernetes Service Discovery (`kubernetes_sd_configs`):**
   * Prometheus đăng ký lắng nghe (Watch API) trực tiếp từ **Kubernetes API Server**.
   * Khi HPA (Horizontal Pod Autoscaler) kích hoạt, 1000 Pods mới được sinh ra, K8s API Server lập tức gửi thông báo (event) về IP và Port của từng Pod cho Prometheus.
   * Prometheus tự động cập nhật danh sách targets trong bộ nhớ chỉ sau vài mili-giây và tiến hành scrape. Khi Pod bị tiêu hủy (scale-down), Prometheus tự động loại khỏi target list.
2. **Consul / Eureka Service Discovery (`consul_sd_configs` / `eureka`):**
   * Trong môi trường Microservices truyền thống (VMs, Docker Swarm), các service khi khởi động sẽ tự đăng ký vào Service Registry (Consul, Eureka).
   * Prometheus định kỳ truy vấn Service Registry này để lấy toàn bộ danh sách service sống.
3. **Cloud Providers SD (AWS EC2/ECS, GCP GCE, Azure):**
   * Prometheus tự động quét các máy ảo / container thông qua Tag/Metadata của Cloud provider.

> **Kết luận:** Trong mô hình Auto-scaling, Prometheus không cần ai phải sửa file cấu hình tĩnh, mà tự động thích ứng với biến động hàng ngàn nodes/pods thông qua **Service Discovery**.

---

## 4. Khi nào bắt buộc phải dùng Push (Prometheus Pushgateway)?

Mô hình Pull có một "gót chân Asin": **Thời gian tồn tại của tiến trình (Lifecycle)**.

* **Vấn đề của Short-lived / Ephemeral Jobs:**
  * Giả sử bạn có các tiến trình chạy ngầm: Cronjob dọn dẹp database, Batch Job tính toán thanh toán, CI/CD pipeline script, AWS Lambda / Serverless function.
  * Các job này chỉ thực thi trong thời gian cực ngắn (vài trăm mili-giây đến vài giây) rồi tắt ngay lập tức.
  * Nếu Prometheus có chu kỳ scrape là 15 giây, thì 100% khả năng tiến trình đã chạy xong và tắt mất trước khi Prometheus kịp ghé thăm. Toàn bộ dữ liệu đo đạc sẽ bị biến mất.

* **Giải pháp: Prometheus Pushgateway**
  * Trong trường hợp này, kiến trúc chuyển sang mô hình kết hợp (Hybrid):
    1. Short-lived Job hoàn thành công việc và chủ động **PUSH** metrics của nó vào **Pushgateway** trước khi thoát.
    2. Pushgateway đóng vai trò như một **bộ nhớ đệm tạm thời (Metrics Cache)**.
    3. Prometheus vẫn giữ nguyên mô hình **PULL** định kỳ từ Pushgateway như một target thông thường.
  * *Lưu ý sống còn:* Không dùng Pushgateway cho microservices chạy dài hạn, vì sẽ biến Pushgateway thành Single Point of Failure và điểm nghẽn cổ chai (bottleneck).

---

## 5. Bảng so sánh tổng hợp: Pull-based vs Push-based

| Tiêu chí | Mô hình Pull (Prometheus) | Mô hình Push (DataDog, CloudWatch, Telegraf) |
| :--- | :--- | :--- |
| **Đơn vị chủ động** | **Monitoring Server** chủ động đi lấy dữ liệu theo lịch trình | **Ứng dụng / Agent** chủ động gửi dữ liệu lên server |
| **Kiểm soát tải (Rate Control)** | **Rất tốt**: Server tự quyết định tần suất, không sợ bị dội bom tải | **Kém**: Hàng ngàn client gửi đồng loạt dễ gây nghẽn cổ chai |
| **Độ an toàn cho Backend** | **Tuyệt đối**: Monitoring chết không ảnh hưởng gì tới hoạt động của app | **Rủi ro**: Server nhận chết có thể khiến client bị nghẽn buffer, tràn RAM |
| **Quản lý Dynamic Target (Auto-scaling)** | Cực mạnh khi kết hợp **Service Discovery** (K8s API, Consul, AWS) | Tự nhiên (service mới tự gửi kèm thông tin nhận diện) |
| **Phát hiện sập nguồn (Liveness)** | Tự động phát hiện ngay lập tức (`up == 0` khi connection timeout) | Khó phân biệt giữa service sập và mạng bị nghẽn/chậm |
| **Bảo mật & Tường lửa (Firewall)** | Server cần mở đường kết nối vào App (Pull). Backend chỉ cần mở port nội bộ | App chủ động kết nối ra ngoài (Egress), cấu hình Firewall đơn giản hơn |
| **Xử lý Short-lived Jobs / Serverless** | Cần giải pháp phụ trợ (**Pushgateway**) | Hoạt động tự nhiên, phù hợp với Serverless / Cronjob |
| **Đại diện tiêu biểu** | **Prometheus**, VictoriaMetrics, Thanos | **DataDog**, AWS CloudWatch, InfluxDB Telegraf, New Relic |

---

## 6. Kết luận & Bảo vệ kiến trúc

1. **Khẳng định giá trị cốt lõi:** Việc lựa chọn **Prometheus với cơ chế Pull** là quyết định chuẩn mực cho hạ tầng Microservices và Cloud-Native (Kubernetes). Nó đảm bảo tính ổn định tối đa cho sản phẩm kinh doanh cốt lõi (Core Backend không bao giờ bị sập lây do hệ thống monitor).
2. **Hóa giải nỗi lo Auto-scaling:** Đã có **Kubernetes Service Discovery** tự động phát hiện hàng nghìn pod trong chớp mắt mà không tốn công quản trị thủ công.
3. **Độ linh hoạt:** Với các tác vụ đặc thù vòng đời ngắn (Cronjob), hệ sinh thái đã có sẵn **Pushgateway** giải quyết trọn vẹn mà không cần thay đổi kiến trúc tổng thể.
