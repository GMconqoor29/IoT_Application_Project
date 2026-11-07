# Quy chuẩn giao tiếp MQTT (Topics & Payload)

## Thông tin kết nối Local
- **Host:** `localhost` (hoặc IP LAN của máy tính khi cấu hình trên ESP32-S3, ví dụ `192.168.1.32`)
- **Port TCP (ESP32 / Backend / MQTTX):** `1883`
- **Port WebSockets (MQTTX):** `9001`

## Danh sách Topics chuẩn AirSense
| Topic | Chiều gửi | QoS | Mô tả |
| :--- | :--- | :---: | :--- |
| `airsense/{deviceId}/telemetry` | Firmware $\rightarrow$ Broker $\rightarrow$ Backend | 1 | Gửi dữ liệu cảm biến định kỳ (JSON khớp `contracts/schemas/telemetry.schema.json`) |
| `airsense/{deviceId}/state` | Firmware $\rightarrow$ Broker $\rightarrow$ Backend | 1 | Trạng thái kết nối (`online`, `bootId`, `queueDepth`) |
| `airsense/{deviceId}/metadata` | Firmware $\rightarrow$ Broker $\rightarrow$ Backend | 1 | Thông tin phần cứng, firmware version, capabilities |
| `airsense/{deviceId}/receipts` | Backend $\rightarrow$ Broker $\rightarrow$ Firmware | 1 | Phản hồi xác nhận đã lưu DB (`COMMITTED`) hoặc từ chối (`ID_CONFLICT`, v.v.) |
