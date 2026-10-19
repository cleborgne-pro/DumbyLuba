# Robot camera (debug)

Service → Robot → **Camera** (user guide: [robot camera](features/camera.md)). Shows the LUBA's video stream like the Mammotion app: **Agora** video relayed by the cloud, never over Bluetooth.

## Sequence (PyMammotion `client.get_stream_subscription`, Mammotion-HA `camera.py`)

1. **Token**: `POST https://domestic.mammotion.com/device-server/v1/stream/token`, account token (`Authorization: Bearer`), body `{"deviceId": iotId, "mode": 0, "cameraStates": [{"cameraState":1},{"cameraState":0},{"cameraState":0}]}`. Response: the viewer's `appid`, `channelName`, `token` and `uid`, `openEncrypt` + `key`/`salt` (AES-256-GCM2), `availableTime` (4G quota), `cameras`. The robot publishes camera n under Agora uid n + 1 (1 = front/left, 2 = right). Code `50504`: the cloud cannot reach the robot.
2. **Publishing**: the app sends `SocMul.set_video{position=LEFT, vi_switch=1}` (`MSG_CMD_TYPE_MUL` → `SOC_MODULE_MULTIMEDIA`) after the token, unless the robot already reports the video as active (`rpt_dev_status.fpv_info.fpv_flag` = 1). Requesting the token alone is not enough: on Oct 13, `fpv_info` was present but `fpv_flag` stayed at 0 after a `vi_switch=0`, the channel stayed without picture and the stream only restarted with a `vi_switch=1` sent by hand (`fpv_flag` switched to 1 within 2 s). With no picture after 25 s, `vi_switch=1` is resent in every case. The "Force vi_switch=1" button sends it by hand.
3. **Playback**: a single WebView (`CameraController`) loads `assets/camera/agora.html` (Agora Web SDK 4.24.3 downloaded from the Agora CDN on first use), joins the channel with the viewer uid (`mode live`, like the Mammotion-HA card) and shows the chosen camera. Measured on the Fold on 2026-10-12: H.265 640 × 480 stream decoded by the hardware decoder, first frame ≈ 13 s after the page opens.
4. **Keep-alive**: on 4G (`rpt_connect_status.used_net` = MNET), `refresh_fpv` (`SocMul.req_encode`) every 3 s; without a robot link, a cloud `ble_sync(3)` is sent at startup.
5. **Stop**: the page leaves the channel, *then* `vi_switch=0` (in that order, otherwise the robot rejoins and immediately leaves). Leaving the page also stops the stream.

## Right camera (uid 2)

The robot only publishes the cameras requested in the token's `cameraStates`: `[1,0,0]` (like the Mammotion app) gives only the
front camera (uid 1). Choosing "Right (2)" requests `[1,1,0]` (PyMammotion `all_cameras`), and restarts the stream if it had been
requested without it. Verified on the LUBA 2 AWD on Oct 13: cameras 1 and 2 published, right picture 640×480.

## Black screen with "Camera live" (fixed on Oct 13)

The player WebView must have `MATCH_PARENT` layout parameters: inserted by Compose (`AndroidView`) without parameters, it
is `WRAP_CONTENT` and Chromium brings the CSS viewport height down to 0. The video (`height: 100 %`) was then decoded
(first frame reported) but displayed on 0 pixels, with the player text stuck at the top of the area. The player state now
shows the picture size and the area size (English interface: `Camera 1 live · frame 640×480 · area …×… px`).

## Timeouts (since Oct 13)

Account robot list 25 s, video token 30 s (the cloud waits for the robot: 50504 if it does not respond), Agora player 20 s
(reloaded if the SDK could not load), joining the channel 20 s, first frame 25 s, then `vi_switch=1` requested again and
failure at 45 s. Player state changes (channel, published cameras, Agora errors) are logged (`Camera: player`). Each step is shown under "State"; "Cancel the request" stops a request in progress; code 460
renews the session as PyMammotion does.

## Obstacle photo after a bumper contact

Local route (tracked by the phone or a leg delegated to the gateway) and move to a point with a physical guard:

1. **Contact** (code 1014): in addition to the usual stop, the app requests the video stream (cloud token) so it is ready after the back-off.
2. Usual straight **back-off** (`reverseStraight`), then a stop confirmed by two stationary positions.
3. **Wait**: the robot stays stationary, blades stopped (zero speed, manual mode without blades and error polling every 500 ms), for at most **25 s**. The photo is taken as soon as a **displayed** frame shows the robot stopped: the player timestamps each displayed frame (`requestVideoFrameCallback`) and reads Agora's end-to-end delay (`end2EndDelay`); the frame is kept if its time minus this delay (+ 0.5 s; 1.5 s if unknown; 6 s at most) comes after the end of the back-off (`ObstaclePhotoUi.sceneAfterStop`). A frozen frame (stalled stream, screen off) is never kept; in the last 2 seconds, the latest frame is taken anyway and flagged "possibly earlier than the back-off" (`fresh=false` in the log).
4. **Camera**: "Photo camera" setting (Service → Obstacles and Service → Camera, saved), front/left by default or right.
   Right: the stream is requested with both front cameras (restarted if it was open without it) and the photo is taken on uid 2;
   if it yields no frame, the front camera is used as a fallback in the last 4 seconds of the wait. The status and the log
   (`obstacle_photo`: `camera`, `wantedCamera`) state which camera was used.
5. **Resume** of the repaired route, whether or not a photo was taken (account not connected, robot offline, screen off: the WebView must be displayed to decode).
6. **Notification** "Obstacle encountered" (WARNING, one per robot, updated on each contact, silent repeats for 15 min): contact position, delay and camera, **photo** as a thumbnail and full size once expanded; without a photo, the reason (disabled, account not connected, no frame…).

Photos: `filesDir/diagnostics/obstacle-photos/<photo>_<contact>_<code>_<x mm>_<y mm>.jpg` (last 50, included in the diagnostic archive), copied to `Android/data/fr.luba.poc/files/obstacle-photos` (`adb pull`). List and enlarged view in Service → Obstacles, with the nearest recorded obstacle. "Photograph the obstacle after the back-off" setting (on by default) in Service → Obstacles and Service → Camera. Log: `obstacle_photo_request`, `obstacle_photo`, `obstacle_photo_missed`, `obstacle_photo_failed`.

## Live view on the map

Thumbnail at the top right during a movement (route, mowing, move to a point, learning), full size in
manual control, stopped 15 s after the end of the movement (`camera/LiveCamera.kt`). The **camera button** always stays at the top right
of the map (below the status pill; the view appears to its left), including when the robot is stopped: it shows or hides the view. When stopped, it requests the stream on
demand, held until the view is hidden (immediate stop) or the map has been left for 15 s. Hiding the automatic view during a
movement also turns it off for the following movements, until the next tap. Never on 4G (quota) nor without a Mammotion account:
the view then shows the reason.

A single thumbnail on the map: no close button (the camera button, green when the view is shown, hides it), with a loading animation (green arc around a camera) until the first frame, and only an icon with a short reason when the stream is impossible (account not connected, robot on 4G, failure). The view counts as displayed as soon as it starts loading, and the stream of a view hidden while stopping has no thumbnail: the fallback thumbnail never again duplicates the top one (before Oct 13, it flickered at the bottom while both fought over the player).

The activity is `singleTask`: a second stacked window (notification from a service, `am start`) also requested the stream and kept the player off screen (log `Camera 2 live · frame 640×480 · area 0×0 px` in today's English wording, duplicated camera events), and loading never finished (observed on Oct 13).

**Reconnection (since Oct 15, 0.43.0).** The view stayed in loading state when returning to the app, on the map or
in manual control: the stream was only requested on the rising edge of the request (`LaunchedEffect(liveWanted)`). Stopped
in the meantime (obstacle photo finished after 10 s, Camera page left, failure) while the view was still wanted,
nothing requested it again, and the "Camera stopped" state was shown as loading. On return from background, the previous Agora session
could also remain frozen (last frame, or robot gone from the channel) without a restart. Now (`CameraHost`):

- **supervisor** (`LiveCamera.supervise`): as long as the view is wanted, a missing stream is requested and a stream already
  open (photo, Camera page) is taken over (`ADOPT`) instead of being cut; the Camera page and stopping the photo stream
  no longer cut a stream that the live view wants (`LiveCamera.wantedNow`). A failure is only retried once a
  new attempt is allowed (`LiveCamera.retry`): return to the foreground, return to the map or to manual control, tap
  on the view, new request;
- **background** (`ON_STOP`, except activity recreation on folding): the live view stream is cut
  (`vi_switch=0`), then requested again on return; log `Camera: live view cut, app in the background`;
- **watchdog** for a displayed stream, every 2 s: the page's `health()` returns the time of the last displayed frame
  (`requestVideoFrameCallback`), the Agora connection state and the published cameras. No frame for 6 s:
  `replay()` redisplays the camera in a new video element; after 12 s, or on a `DISCONNECTED` connection: new
  token (3 times at most, then "Video stream failed: frozen frame after 3 restarts"). Log `Camera: frozen frame, …`;
- view requested by hand: it holds for 15 s away from the map (quick return without a cut), then the stream stops immediately.

**Movable and resizable window (0.58.0).** The map thumbnail and the fallback thumbnail are
floating windows (`camera/CameraWindow.kt`): dragging the window moves it; the handle in the corner opposite the
anchor (bottom left on the map, anchored at the top right; top right for the fallback thumbnail, anchored at the bottom
left) or a two-finger pinch resizes it at a 16:9 ratio (112 dp minimum, never larger than
the screen), with the anchor corner staying fixed; a double tap on the handle restores the original position and size.
Offset from the anchor and width are saved in dp per window (`camera_window`: `map.*`, `fallback.*`) and
clamped to the hosting screen on each placement (Fold folding, rotation). Gestures are read in the `Initial` pass,
before the content: a simple tap goes to the player or to restarting a failed stream; a drag beyond the
threshold is taken away from the WebView and no longer moves the map underneath. The camera label moves to the bottom right
(the handle takes the bottom-left corner). Tests: `CameraWindowTest` (geometry), `CameraWindowGestureTest`
(drag, screen edge, handle, simple tap).

A fallback stream thumbnail appears at the bottom left while the camera is running outside the Camera page and the map (or for an obstacle photo when the view is hidden); a stream started for a photo stops by itself 60 s after the last contact during a route (10 s otherwise).

## Links and safety

- Video commands go over the robot link (direct Bluetooth or gateway) if it is ready, otherwise over cloud MQTT. The `CloudReadOnly` barrier only accepts, in addition to reads, `set_video` (position LEFT, 0/1) and `req_encode`: no movement or blade command.
- The token is never logged: the log (`camera`) only keeps a summary (viewer, cameras, encryption, quota).
- Session revoked (same account signed in from another build of the app or from the Mammotion app): the token
  request gets 401/460, the refresh token is refused, and since 1.1.3 the app signs in again with the saved account
  and replays the token request. Before, it signed the account out and the video failed with "Sign in to your
  Mammotion account (Me tab)" (Fold, Oct 19: published build 1.0.1 and Fold build 1.1.2 installed side by side).
- Prerequisites: Mammotion account connected (Me tab), robot online (Wi-Fi or 4G), network on the phone. On 4G, video time is deducted from the Mammotion quota.

Code: `camera/CameraStream.kt` (token), `camera/CameraRobot.kt` (fpv_info, network, acknowledgement), `camera/CameraController.kt` (shared player), `camera/CameraHost.kt` (stream, photo, thumbnail), `camera/CameraPage.kt` (page), `camera/ObstaclePhotoStore.kt` (photos), `assets/camera/agora.html`. Tests: `CameraTest`, `ObstaclePhotoStoreTest`, `LiveCameraTest`.
