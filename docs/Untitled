Notes for Q3 reference students of the March 31st version:
Currently, there are two versions of the reference student model online, and the characteristics of each version are as follows:
  - viduq3-mix: strong visual quality, supports intelligent scene transitions, good dynamic effects, and the strongest balance
  - viduq3: Supports intelligent camera switching, achieving better consistency across multiple camera positions
2. The viduq3-mix model currently does not support calling the subject library, and is undergoing continuous iteration.
Request URL
POST https://api.vidu.com/ent/v2/reference2video
Request Header
Field
Value
Description
Content-Type
application/json
Data Exchange Format
Authorization
Token {your api key}
Replace {} with your API key

Field
Type
Required
Description
model
String

Required
Model name
Accepted values: viduq3-mix viduq3 viduq2-pro viduq2 viduq1 vidu2.0
-Viduq3-mix: strong visual quality, supports intelligent scene transitions, good dynamic effects, and the strongest balance
-Viduq3: Supports intelligent camera switching, achieving better consistency across multiple camera positions
-Viduq2-pro: Supports video reference, video editing, and video replacement
-Viduq2: Good dynamic effect and rich generated details
-Viduq1: Clear image, smooth transition, stable camera operation
-Vidu2.0: Fast generation speed
images
Array[String]
Optional
The model will use the provided images as references to generate a video with consistent subjects
For fields that accept images:
- viduq3-mix viduq3 viduq2 viduq1 vidu2.0 accepts 1 to 7 images.
- viduq2-pro If no video is uploaded, supports 1-7 images ; If uploading videos, supports 1-4 images.
- Assets can be provided via URLs or Base64 encode.
- You must use one of the following codecs: PNG, JPEG, JPG, WebP
- The dimensions of the images must be at least 128x128 pixels
- The aspect ratio of the images must be less than 1:4 or 4:1
- All images are limited to 50MB
- The length of the base64 decode must be under 10MB, and it must include an appropriate content type string. For instance, data:image/png;base64,{base64_encode}
videos
Array[String]
Optional
The video URL corresponds to the subject. All subjects share a 13s duration (each image subject = 1s). Must provide either this or images.
- Note 1: This parameter is only supported by the viduq2-pro model.
- Note 2: When using the video reference feature, supports uploading at most 1 video of 8s or 2 videos of 5s.
- Note 3: Supported video formats: mp4, avi, mov.
- Note 4: Video resolution cannot be less than 128*128, aspect ratio must be between 1:4 and 4:1, and size must not exceed 100M.
- Note 5: Please note that the byte length after base64 decoding must be less than 20M, and the encoding must include the appropriate content type string, e.g.: data:video/mp4;base64,{base64_encode}
prompt
String
Required
Text prompt
A textual description for video generation, with a maximum length of 5000 characters
duration
Int
Optional
Video duration parameter, with default values depending on the model:
- viduq3-mix: Default is 5 seconds, available option:1-16
- viduq3: Default is 5 seconds, available option:3-16
- viduq2-pro: Default is 5 seconds, available option: 0-10(0 for automatic duration)
- viduq2: Default is 5 seconds, available option: 1-10
- viduq1: Default is 5 seconds, available option: 5
- vidu2.0: Default is 4 seconds, available option: 4
seed
Int
Optional
Random seed
- Defaults to a random seed number
- Manually set values will override the default random seed
aspect_ratio
String
Optional
The aspect ratio of the output video
Defaults to 16:9, accepted: 16:9 9:16 3:4 4:3 1:1
3:4&4:3 only support q2 q3 models
resolution
String
Optional
Resolution parameter, with default values depending on the model and video duration:
- viduq3-mix(1 - 16s): Default is 720p, available option: 720p 1080p
- viduq3(3 - 16s): Default is 720p, available option: 540p 720p 1080p
- viduq2-pro (0 - 10s): Default is 720p, available option: 540p 720p 1080p
- viduq2 (1 - 10s): Default is 720p, available option: 540p 720p 1080p
- viduq1 (5s): Default is 1080p, available option: 1080p
- vidu2.0 (4s): Default is 360p, available options: 360p, 720p
movement_amplitude
String
Optional
The movement amplitude of objects in the frame
Defaults to auto, accepted value: auto small medium large
This parameter does not take effect when using the q2 q3 models
bgm
Bool

Optional
Whether to add background music to the generated video. Ineffective for q3 model
Default: false. Acceptable values: true, false.
When true, the system will automatically add a suitable BGM.
BGM has no time limit and the system automatically adapts.
BGM does not take effect when the duration of the q2 model is 9 or 10 seconds；BGM does not available in q3 models
payload
String
Optional
transparent transmission parameters
No processing, only data transmission，with a maximum length of 1048576 characters
off_peak
Bool
Optional
off peak mode, Defaults to false, accepted value: true false
- true：off peak generate mode；
- false：normal generate mode；
- The offpeak mode consumes lower points, please refer to the details Pricing. Tasks submitted in off peak mode will be generated within 48 hours. Tasks that are not completed will be automatically cancelled and their points will be refunded. We also support  cancel off_peak tasks.
- Except for q3, other direct audio-video generation functions do not support off-peak mode；viduq3-mix do not support off-peak mode
callback_url
String
Optional
Callback
When creating a task, you need to actively set the callback_url with a POST request. When the video generation task changes its status, Vidu will send a callback request to this URL, containing the latest status of the task. The structure of the callback request content will be the same as the return body of the GET Generation API.
The "status" in the callback response includes the following states:
- processing: Task is being processed.
- success: Task is completed (if sending fails, it will retry the callback three times).
- failed: Task failed (if sending fails, it will retry the callback three times).
Vidu uses a callback signature algorithm for verification, check out the details here: Callback Signature
curl -X POST -H "Authorization: Token {your_api_key}" -H "Content-Type: application/json" -d '
{
    "model": "viduq3",
    "images": ["https://prod-ss-images.s3.cn-northwest-1.amazonaws.com.cn/vidu-maas/template/reference2video-1.png","https://prod-ss-images.s3.cn-northwest-1.amazonaws.com.cn/vidu-maas/template/reference2video-2.png","https://prod-ss-images.s3.cn-northwest-1.amazonaws.com.cn/vidu-maas/template/reference2video-3.png"],
    "prompt": "Santa Claus and the bear hug by the lakeside.",
    "duration": 5,
    "seed": 0,
    "aspect_ratio": "16:9",
    "resolution": "720p",
    "audio": true,
    "movement_amplitude": "auto",
    "off_peak": false
}' https://api.vidu.com/ent/v2/reference2video

Response Body
Field
Type
Description
task_id
String
Task ID
state
String
It will be returned to a specific processing state:
- created created task successfully
- queueing task in queue
- processing processing
- success generation successful
- failedtask failed
model
String
The parameter of the model used for this call
images
Array[String]
The image used for this call
prompt
String
The text prompt used for this call
duration
Int
The video duration parameter used for this call
seed
Int
The random seed parameter used for this call
aspect_ratio
String
The aspect ratio parameter used for this call
resolution
String
The resolution parameter used for this call
bgm
Bool
The bgm parameter used for this call
movement_amplitude
String
The camera movement amplitude parameter used for this call
payload
String
The payload parameter used for this call
off_peak
Bool
The off_peak parameter used for this call
credits
Int
The credits used for this call
watermark
Bool
The watermarks used for this call
created_at
String
Task creation time
{
  "task_id": "your_task_id_here",
  "state": "created",
  "model": "viduq3",
  "images": ["https://prod-ss-images.s3.cn-northwest-1.amazonaws.com.cn/vidu-maas/template/reference2video-1.png","https://prod-ss-images.s3.cn-northwest-1.amazonaws.com.cn/vidu-maas/template/reference2video-2.png","https://prod-ss-images.s3.cn-northwest-1.amazonaws.com.cn/vidu-maas/template/reference2video-3.png"],
  "prompt": "Santa Claus and the bear hug by the lakeside.",
  "duration": 5,
  "seed": random_number,
  "aspect_ratio": "16:9",
  "resolution": "720p",
  "audio": true,
  "movement_amplitude": "auto",
  "payload":"",
  "off_peak": false,
  "credits": credits_number,
  "created_at": "2025-01-01T15:41:31.968916Z"
}

Pricing
Model
resolution
credits / s
price
viduq3-mix
1080p
30
$0.15 / s

720p
25
$0.125 / s
viduq3
1080p
25
$0.125 / s

720p
20
$0.1 / s

540p
10
$0.05 / s