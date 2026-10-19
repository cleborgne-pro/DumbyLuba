# References and license

This POC is distributed under GPL-3.0 (see LICENSE), because it includes PyMammotion's GPL-3.0 protobuf schemas.

Source of the schemas: https://github.com/mikey0000/PyMammotion/tree/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/proto

The `.proto` files are copied in full; only `java_package` and `java_outer_classname` are added. The Kotlin implementations of the transport, commands and telemetry follow the messages and framing of these references. The CRC/BluFi vectors come from running the upstream methods with the provided script. All POC sources accompany the APK in the repository.

Error code table: `pymammotion/data/error_codes.csv` (PyMammotion, GPL-3.0, commit 967c7ac9bc67b11b5d883700b0380fa29ae8f5e4), reduced to the English and French labels and advice in `app/src/main/resources/fr/luba/poc/protocol/robot_error_codes.tsv` by `tools/build-robot-error-codes.py`; original Mammotion texts. Additional translations specific to the project in `tools/robot-error-codes-fr.tsv`.

Mammotion-HA and Mammo are inspected references; none of their files are incorporated. The Android/Compose, Kotlin, protobuf and JUnit dependencies are resolved through Gradle and keep their respective licenses.

- Eclipse Paho Java MQTT client 1.2.5: https://github.com/eclipse-paho/paho.mqtt.java — EPL 1.0 / EDL 1.0. MQTT transport of the cloud POC.
- Auth v2 and client constants: PyMammotion 0.10.8 distributed on PyPI, under the upstream project's license (GPL-3.0), port faithful to `http.py`. No personal credentials are embedded. Account reading and cloud action encodings: PyMammotion 0.10.10 and Mammotion-HA 0.6.17 ([details](docs/MAMMOTION_ACCOUNT.md)).

- LocationTech JTS Core 1.20.0: https://github.com/locationtech/jts — EPL 2.0 / EDL 1.0. Metric buffers and coverage intersections.
- Protocol Buffers (`protobuf-java` and `protoc` 3.25.5): https://github.com/protocolbuffers/protobuf — BSD 3-Clause. Code generated from the PyMammotion schemas.

## ESP32 firmware and enclosure viewer

- NimBLE-Arduino 1.4.3 (`h2zero/NimBLE-Arduino`, resolved by PlatformIO, Apache 2.0): Bluetooth LE stack of the ESP32 gateway and diagnostics firmware (`firmware/*/platformio.ini`).
- three.js 0.169.0 (MIT): loaded from the jsDelivr CDN by the enclosure's 3D viewer (`hardware/luba-esp32-qi/viewer/`), not incorporated in the repository.

## Robot camera (Agora)

No video SDK is incorporated in the APK. The camera (Service → **Camera**, and the thumbnails on the map and in manual control) loads Agora Web SDK 4.24.3 at runtime (`https://download.agora.io/sdk/release/AgoraRTC_N-4.24.3.js`, Agora.io, proprietary Agora license) in a WebView, like the Mammotion-HA Lovelace card (`ha-mammotion-assets/dist/agora-client.js`, inspected, not incorporated). Token sequence and video command: PyMammotion `http.py` (`get_stream_subscription`) and `commands/messages/video.py`.

## Robot image

`app/src/main/res/drawable/robot_illustration.xml`: original vector drawing by the project (GPL-3.0), shown when the model is unknown.

`app/src/main/res/drawable/robot_photo_*.webp`: Mammotion product photos (official stores us/de/fr.mammotion.com; 1st-generation LUBA AWD: product page of the reseller heyupnow.com), cut out and downscaled for the home screen. These images remain the property of Mammotion and are not covered by the project's GPL-3.0; they are included only to show the detected model, and will be removed on request of the rights holder. The app looks them up by name (`RobotModel.image`) and shows the project's illustration when they are missing; the detected model is still named. Table of model prefixes and product keys: `pymammotion/utility/device_type.py` (PyMammotion, GPL-3.0). "Mammotion" and "LUBA" are trademarks of their respective owners, mentioned only to indicate compatibility; this project is neither affiliated with nor endorsed by Mammotion.

## Weather data (rain check)

No data is incorporated in the APK; it is downloaded at runtime and credited in the "Garden weather" details and the Service page "Rain and weather".

- Observed rain: RainViewer Weather Maps API (https://www.rainviewer.com/api.html), free tier for personal use, RainViewer credit displayed. Universal Blue palette: table https://www.rainviewer.com/files/rainviewer_api_colors_table.csv, reproduced in `weather/RadarMath.kt` to convert colors to dBZ.
- Precipitation forecast: Open-Meteo (https://open-meteo.com), data under CC BY 4.0, free for non-commercial use.
