# Robot camera

Shows the LUBA 2 AWD cameras live, like the Mammotion app, and can photograph an obstacle after a bumper contact.

| | |
|---|---|
| **Access** | Service → Robot → **Camera**; camera button on the map; large view in [manual control](manual-control.md) |
| **Status** | Implemented; front and right streams verified on a LUBA 2 AWD (H.265 640 × 480) |
| **Prerequisites** | [Mammotion account](mammotion-account.md) signed in, robot online (Wi-Fi or 4G), Internet on the phone |
| **Code** | `camera/` (`CameraPage.kt`, `CameraController.kt`, `CameraHost.kt`, `LiveCamera.kt`, `CameraWindow.kt`, `CameraStream.kt`, `CameraRobot.kt`, `ObstaclePhotoStore.kt`), `assets/camera/agora.html` |
| **Reference** | [Camera: sequence, timings and troubleshooting](../CAMERA.md) |

## Usage

- **Show camera** requests a video token from the cloud, enables video on the robot, then starts the player.
  The first frame arrives in about ten seconds.
- **Displayed camera**: *Front / left (1)* or *Right (2)*. The **Also request the right camera** switch requests
  both streams from the start.
- **Cancel the request** (while waiting), **Stop the stream**, **Restart (new token)**; **Force vi_switch=1**
  resends the activation command manually if the channel stays black. **Show camera** and **Restart** require the
  Mammotion account; without it the page says so.
- **Stream** group: state of each step, player state, reception, published cameras, session, account robot, last
  command. **Robot (telemetry)** group: link used for the video commands (robot over Bluetooth / gateway, otherwise
  the Mammotion cloud), `fpv_info`, robot network (`refresh_fpv` every 3 s on 4G), last `vi_switch` acknowledgement.
- The **Obstacle photo after a contact** group repeats the two photo settings below.
- Leaving the page stops the stream, unless an obstacle photo or the live view is using it.

**Thumbnail while driving**: when the Camera page is closed, the stream stays on screen as a thumbnail on top of the
app ("Robot camera"). [Manual control](manual-control.md) shows the view large above the joysticks, which lets
you drive the robot remotely while watching what it sees.

**Moving and resizing the thumbnail** (the map thumbnail and the "Robot camera" thumbnail on other screens):
dragging the thumbnail moves it; dragging the handle in its corner (bottom left on the map, top right elsewhere) or
pinching with two fingers enlarges or shrinks it, at a 16:9 ratio. A double tap on the handle restores its original
position and size. Position and size are saved per thumbnail and stay within the screen (folding, rotation). A
single tap on the thumbnail keeps its usual effect (restarting a failed stream).

Video goes through the cloud and Agora, never over Bluetooth. Activation commands go over Bluetooth or the gateway
if the robot is connected, otherwise through the cloud. On 4G, video counts against the Mammotion data quota.

## Obstacle photo after a contact

During a local route or a move to a point, a bumper contact triggers the
[back-off](obstacles.md#contact-during-a-mission-back-off-and-bypass). If the option is enabled:

1. the video stream is requested as soon as the contact occurs, so it is ready after the back-off;
2. the robot stays still, blades stopped, for at most 25 s;
3. the photo is taken from the first displayed frame filmed after the end of the back-off (frame time minus the
   Agora delay + 0.5 s, 1.5 s when unknown, 6 s at most); in the last 2 s, the latest frame is kept anyway and flagged;
4. the repaired route resumes, whether or not a photo was taken;
5. an **Obstacle encountered** notification shows the contact position and the photo, or the reason why there is
   none ([notifications](notifications.md)).

| Setting | Default |
|---|---|
| Photograph the obstacle after the back-off | enabled |
| Photo camera | Front / left (Right possible: both front cameras are requested, fallback to the front in the last 4 s) |

Both settings are saved and shared by Service → Camera and Service → Obstacles. Photos (at most 50, 4 MB each) can
be viewed in Service → **Obstacles** (12 latest, enlarged on tap, nearest saved obstacle named), on the contact's
record on the map, and are included in the ZIP export of the [logs](logs-and-data.md).

## Live view on the map

- **Automatic** during a robot movement (route, mowing, move to a point, learning): a thumbnail at the top right of
  the map, stopped 15 s after the end of the movement.
- **Camera button** at the top right of the map (always visible, green when the view is shown): shows or hides the
  view. With the robot stopped, it requests the stream on demand, held until the view is hidden or the map has been
  left for 15 s. Hiding the automatic view also hides it for the next movements, until the next tap (setting kept).
- **Manual control**: large view above the joysticks while the page is open.
- Never on 4G (Mammotion quota) nor without a Mammotion account: the view shows the reason. A loading animation
  (green arc around a camera) runs until the first frame.
- Outside the map and manual control, a running stream (photo, Camera page left during a movement) appears as the
  "Robot camera" thumbnail.

## Live view on the map and in manual control: reconnection

The live view (map thumbnail, large view in manual control) reconnects on its own:

- **back in the app** (screen turned back on, another app): the stream is cut when the app goes to the background,
  then requested again on return;
- **back on the map or in manual control**: a stream stopped in the meantime (obstacle photo finished, Camera page
  left) is requested again, a stream still open is reused; a view requested manually lasts 15 s outside the map;
- **frozen image** (no new frame for 6 s, robot dropped from the channel, Agora connection lost): the camera is
  shown again, then the stream is restarted with a new token (at most 3 restarts);
- **failure**: the view says so; tapping it sends a new request.

Before Oct 15, the stream was only requested at the start of the move or the press: if it had been stopped in the
meantime, the view kept loading until it was hidden.

## Limitations

- The player loads the **Agora Web SDK** (proprietary license, not included in the APK) at runtime from the Agora
  CDN.
- The WebView must be on screen to decode: with the screen off, no photo is taken.
- Code `50504` means the cloud cannot reach the robot.
