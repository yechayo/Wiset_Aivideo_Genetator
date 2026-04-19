图生视频
创建任务
POST
/v1/videos/image2video
💡
请您注意，为了保持命名统一，原 model 字段变更为 model_name 字段，未来请您使用该字段来指定需要调用的模型版本。
同时，我们保持了行为上的向前兼容，如您继续使用原 model 字段，不会对接口调用有任何影响、不会有任何异常，等价于 model_name 为空时的默认行为（即调用V1模型）

请求头
Content-Type
string
必填
默认值 application/json
数据交换格式

Authorization
string
必填
鉴权信息，参考接口鉴权

请求体
model_name
string
可选
默认值 kling-v1
模型名称

枚举值：
kling-v1
kling-v1-5
kling-v1-6
kling-v2-master
kling-v2-1
kling-v2-1-master
kling-v2-5-turbo
kling-v2-6
kling-v3
image
string
可选
参考图像

支持传入图片 Base64 编码或图片 URL（确保可访问）
注意：请确保您传递的所有图像数据参数均采用Base64编码格式。若您使用 Base64 方式，请不要在 Base64 编码字符串前添加任何前缀（如 data:image/png;base64,），直接传递 Base64 编码后的字符串即可。
正确的 Base64 编码参数：
iVBORw0KGgoAAAANSUhEUgAAAAUA...
错误的 Base64 编码参数（包含 data: 前缀）：
data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA...
图片格式支持 .jpg / .jpeg / .png
图片文件大小不能超过 10MB，图片宽高尺寸不小于 300px，图片宽高比介于 1:2.5 ~ 2.5:1 之间
image 参数与 image_tail 参数至少二选一，二者不能同时为空
不同模型版本、视频模式支持范围不同，详见能力地图

image_tail
string
可选
参考图像 - 尾帧控制

支持传入图片 Base64 编码或图片 URL（确保可访问）
注意：若您使用 Base64 方式，请不要在 Base64 编码字符串前添加任何前缀（如 data:image/png;base64,），直接传递 Base64 编码后的字符串即可。
图片格式支持 .jpg / .jpeg / .png
图片文件大小不能超过 10MB，图片宽高尺寸不小于 300px
image 参数与 image_tail 参数至少二选一，二者不能同时为空
image_tail 参数、dynamic_masks/static_mask 参数、camera_control 参数三选一，不能同时使用
不同模型版本、视频模式支持范围不同，详见能力地图

multi_shot
boolean
可选
默认值 false
是否生成多镜头视频

当前参数为 true 时，prompt 参数无效，且不支持设定首尾帧生视频

当前参数为 false 时，shot_type 参数及 multi_prompt 参数无效

shot_type
string
可选
分镜方式

枚举值：
customize
intelligence
当 multi_shot 参数为 true 时，当前参数必填

prompt
string
可选
正向文本提示词

💡
Omni模型可通过Prompt与主体、图片、视频等内容实现多种能力：

通过<<<>>>的格式来指定某个主体、图片或视频，如：<<<element_1>>>、<<<image_1>>>、<<<video_1>>>
能力范围详见使用手册：可灵Omni模型使用指南、可灵视频 3.0 Omni 使用指南
不能超过 2500 个字符
当 multi_shot 为 false 或 shot_type 为 intelligence 时不得为空。
用 <<<voice_1>>> 来指定音色，序号同 voice_list 参数所引用音色的排列顺序
一次视频生成任务至多引用 2 个音色；指定音色时，sound 参数值必须为 on
语法结构越简单越好，如：男人<<<voice_1>>>说："你好"
当 voice_list 参数不为空且 prompt 参数中引用音色 ID 时，视频生成任务按"有指定音色"计量计费
不同模型版本、视频模式支持范围不同，详见能力地图

multi_prompt
array
可选
各分镜信息，如提示词、时长等

通过 index、prompt、duration 定义分镜序号及提示词、时长。

最多支持6个分镜，最小支持1个分镜；
每个分镜相关内容的最大长度不超过512；
每个分镜的时长不大于当前任务的总时长，不小于1；
所有分镜的时长之和等于当前任务的总时长;
用key:value承载，格式如下：

"multi_prompt":[
{"index":int,"prompt":"string","duration":"5"},
{"index":int,"prompt":"string","duration":"5"}
]
当 multi_shot 为 true 且 shot_type 为 customize 时当前参数必填

negative_prompt
string
可选
负向文本提示词

不能超过 2500 个字符
建议通过正向提示词中的负向句子补充负向提示信息
element_list
array
可选
参考主体列表，基于主体库中主体的 ID 配置

最多支持 3 个参考主体
主体分为视频角色主体和多图主体，适用范围不同。详见 可灵「主体库 3.0」使用指南。

用 key:value 承载，格式如上：
"element_list":[
  { "element_id": long },
  { "element_id": long }
]
不同模型版本、视频模式支持范围不同，详见能力地图

▾
隐藏 子属性
element_id
long
必填
主体库中的主体 ID

voice_list
array
可选
生成视频时所引用的音色的列表

一次视频生成任务至多引用 2 个音色
当 voice_list 参数不为空且 prompt 参数中引用音色 ID 时，视频生成任务按"有指定音色"计量计费
voice_id 参数值通过音色定制接口返回，也可使用系统预置音色，详见音色定制相关API；非对口型 API 的 voice_id
element_list 与 voice_list 互斥，不能共存
示例：

"voice_list":[
  {"voice_id":"voice_id_1"},
  {"voice_id":"voice_id_2"}
]
不同模型版本、视频模式支持范围不同，详见 能力地图

sound
string
可选
默认值 off
生成视频时是否同时生成声音

枚举值：
on
off
不同模型版本、视频模式支持范围不同，详见 能力地图

cfg_scale
float
可选
默认值 0.5
生成视频的自由度；值越大，模型自由度越小，与用户输入的提示词相关性越强

取值范围：[0, 1]
kling-v2.x 模型不支持当前参数

mode
string
可选
默认值 std
生成视频的模式

枚举值：
std
pro
std：标准模式（标准），基础模式，性价比高
pro：专家模式（高品质），高表现模式，生成视频质量更佳
不同模型版本、视频模式支持范围不同，详见能力地图

static_mask
string
可选
静态笔刷涂抹区域（用户通过运动笔刷涂抹的 mask 图片）

"运动笔刷"能力包含"动态笔刷 dynamic_masks"和"静态笔刷 static_mask"两种

支持传入图片 Base64 编码或图片 URL（确保可访问，格式要求同 image 字段）
图片格式支持 .jpg / .jpeg / .png
图片长宽比必须与输入图片相同（即 image 字段），否则任务失败（failed）
static_mask 和 dynamic_masks.mask 这两张图片的分辨率必须一致，否则任务失败（failed）
不同模型版本、视频模式支持范围不同，详见能力地图

dynamic_masks
array
可选
动态笔刷配置列表

可配置多组（最多 6 组），每组包含"涂抹区域 mask"与"运动轨迹 trajectories"序列
不同模型版本、视频模式支持范围不同，详见能力地图

▾
隐藏 子属性
mask
string
必填
动态笔刷涂抹区域（用户通过运动笔刷涂抹的 mask 图片）

支持传入图片 Base64 编码或图片 URL（确保可访问，格式要求同 image 字段）
图片格式支持 .jpg / .jpeg / .png
图片长宽比必须与输入图片相同（即 image 字段），否则任务失败（failed）
static_mask 和 dynamic_masks.mask 这两张图片的分辨率必须一致，否则任务失败（failed）
trajectories
array
必填
运动轨迹坐标序列

生成 5s 的视频，轨迹长度不超过 77，即坐标个数取值范围：[2, 77]
轨迹坐标系，以图片左下角为坐标原点
注1：坐标点个数越多轨迹刻画越准确，如只有 2 个轨迹点则为这两点连接的直线

注2：轨迹方向以传入顺序为指向，以最先传入的坐标为轨迹起点，依次链接后续坐标形成运动轨迹

▾
隐藏 子属性
x
int
必填
轨迹点横坐标（在像素二维坐标系下，以输入图片 image 左下为原点的像素坐标）

y
int
必填
轨迹点纵坐标（在像素二维坐标系下，以输入图片 image 左下为原点的像素坐标）

camera_control
object
可选
控制摄像机运动的协议（如未指定，模型将根据输入的文本/图片进行智能匹配）

不同模型版本、视频模式支持范围不同，详见能力地图

▾
隐藏 子属性
type
string
必填
预定义的运镜类型

枚举值：
simple
down_back
forward_up
right_turn_forward
left_turn_forward
simple：简单运镜，此类型下可在"config"中六选一进行运镜
down_back：镜头下压并后退 ➡️ 下移拉远，此类型下 config 参数无需填写
forward_up：镜头前进并上仰 ➡️ 推进上移，此类型下 config 参数无需填写
right_turn_forward：先右旋转后前进 ➡️ 右旋推进，此类型下 config 参数无需填写
left_turn_forward：先左旋并前进 ➡️ 左旋推进，此类型下 config 参数无需填写
config
object
可选
包含六个字段，用于指定摄像机在不同方向上的运动或变化

当运镜类型指定 simple 时必填，指定其他类型时不填
以下参数 6 选 1，即只能有一个参数不为 0，其余参数为 0
▾
隐藏 子属性
horizontal
float
可选
水平运镜，控制摄像机在水平方向上的移动量（沿 x 轴平移）

取值范围：[-10, 10]，负值表示向左平移，正值表示向右平移
vertical
float
可选
垂直运镜，控制摄像机在垂直方向上的移动量（沿 y 轴平移）

取值范围：[-10, 10]，负值表示向下平移，正值表示向上平移
pan
float
可选
水平摇镜，控制摄像机在水平面上的旋转量（绕 y 轴旋转）

取值范围：[-10, 10]，负值表示绕 y 轴向左旋转，正值表示绕 y 轴向右旋转
tilt
float
可选
垂直摇镜，控制摄像机在垂直面上的旋转量（沿 x 轴旋转）

取值范围：[-10, 10]，负值表示绕 x 轴向下旋转，正值表示绕 x 轴向上旋转
roll
float
可选
旋转运镜，控制摄像机的滚动量（绕 z 轴旋转）

取值范围：[-10, 10]，负值表示绕 z 轴逆时针旋转，正值表示绕 z 轴顺时针旋转
zoom
float
可选
变焦，控制摄像机的焦距变化，影响视野的远近

取值范围：[-10, 10]，负值表示焦距变长、视野范围变小，正值表示焦距变短、视野范围变大
duration
string
可选
默认值 5
生成视频时长，单位 s

枚举值：
3
4
5
6
7
8
9
10
11
12
13
14
15
不同模型版本、视频模式支持范围不同，详见能力地图

watermark_info
object
可选
是否同时生成含水印的结果

通过enabled参数定义，具体格式如下：
 "watermark_info": { "enabled": boolean } 
true 为生成，false 为不生成
暂不支持自定义水印
callback_url
string
可选
本次任务结果回调通知地址，如果配置，服务端会在任务状态发生变更时主动通知

具体通知的消息 schema 见 Callback 协议
external_task_id
string
可选
自定义任务 ID

用户自定义任务 ID，传入不会覆盖系统生成的任务 ID，但支持通过该 ID 进行任务查询
请注意，单用户下需要保证唯一性
cURL

复制

折叠
curl --location --request POST 'https://api-beijing.klingai.com/v1/videos/image2video' \
--header 'Authorization: Bearer <token>' \
--header 'Content-Type: application/json' \
--data-raw '{
    "model_name": "kling-v2-6",
    "image": "https://p2-kling.klingai.com/kcdn/cdn-kcdn112452/kling-qa-test/multi-2.png",
    "image_tail": "https://p2-kling.klingai.com/kcdn/cdn-kcdn112452/kling-qa-test/multi-1.png",
    "prompt": "镜头拉远，女生微笑",
    "negative_prompt": "",
    "duration": "5",
    "mode": "pro",
    "sound": "off",
    "callback_url": "",
    "external_task_id": ""
}'
200

复制

折叠
{
  "code": 0, // 错误码；具体定义见错误码
  "message": "string", // 错误信息
  "request_id": "string", // 请求ID，系统生成，用于跟踪请求、排查问题
  "data": {
    "task_id": "string", // 任务ID，系统生成
    "task_info": { // 任务创建时的参数信息
      "external_task_id": "string" // 客户自定义任务ID
    },
    "task_status": "string", // 任务状态，枚举值：submitted（已提交）、processing（处理中）、succeed（成功）、failed（失败）
    "created_at": 1722769557708, // 任务创建时间，Unix时间戳、单位ms
    "updated_at": 1722769557708 // 任务更新时间，Unix时间戳、单位ms
  }
}
场景调用示例
多镜头效果的图生视频
curl --location 'https://xxx/v1/videos/image2video' \
--header 'Authorization: Bearer xxx' \
--header 'Content-Type: application/json' \
--data '{
    "model_name": "kling-v3",
    "image": "xxx",
    "prompt": "",
    "multi_shot": "true",
    "shot_type": "customize",
    "multi_prompt": [
        {
            "index": 1,
            "prompt": "Two friends talking under a streetlight at night.  Warm glow, casual poses, no dialogue.",
            "duration": "2"
        },
        {
            "index": 2,
            "prompt": "A runner sprinting through a forest, leaves flying.  Low-angle shot, focus on movement.",
            "duration": "3"
        },
        {
            "index": 3,
            "prompt": "A woman hugging a cat, smiling.  Soft sunlight, cozy home setting, emphasize warmth.",
            "duration": "3"
        },
        {
            "index": 4,
            "prompt": "A door creaking open, shadowy hallway.  Dark tones, minimal details, eerie mood.",
            "duration": "3"
        },
        {
            "index": 5,
            "prompt": "A man slipping on a banana peel, shocked expression.  Exaggerated pose, bright colors.",
            "duration": "3"
        },
        {
            "index": 6,
            "prompt": "A sunset over mountains, small figure walking away.  Wide angle, peaceful atmosphere.",
            "duration": "1"
        }
    ],
    "negative_prompt": "",
    "duration": "15",
    "mode": "pro",
    "sound": "on",
    "callback_url": "",
    "external_task_id": ""
}'
引用主体及主体音色的图生视频
curl --location 'https://xxx/v1/videos/image2video' \
--header 'Authorization: Bearer xxx' \
--header 'Content-Type: application/json' \
--data '{
    "model_name": "kling-v3",
    "image": "xxx",
    "image_tail": "xxx",
    "prompt": "The girl with <<<element_1>>> (using <<<voice_1>>>) communicates with the girl with <<<image_1>>> (using <<<voice_2>>>)",
    "element_list": [
        {
            "element_id": long
        }
    ],
    "voice_list": [
        {
            "voice_id": long
        },
        {
            "voice_id": long
        }
    ],
    "negative_prompt": "xxx",
    "duration": "9",
    "mode": "std",
    "sound": "on",
    "callback_url": "xxx",
    "external_task_id": "",
}'
指定音色生成视频
curl --location 'https://api-beijing.klingai.com/v1/videos/image2video/' \
--header 'Authorization: Bearer {请替换为你的token}' \
--header 'Content-Type: application/json; charset=utf-8' \
--data '{
    "model_name": "kling-v2-6",
    "image": "替换为你的图片链接",
    "prompt": "<<<voice_1>>>让图中人物说出以下文字：'\''热烈欢迎大家'\''",    //若指定台词需要加引号
    "voice_list": [
        {
            "voice_id": "替换为你的音色id"
        }
    ],
    "duration": "5",
    "mode": "pro",
    "sound": "on",
    "callback_url": "",
    "external_task_id": ""
}'
查询任务（单个）
GET
/v1/videos/image2video/{id}
请求头
Content-Type
string
必填
默认值 application/json
数据交换格式

Authorization
string
必填
鉴权信息，参考接口鉴权

路径参数
task_id
string
可选
图生视频的任务 ID

请求路径参数，直接将值填写在请求路径中
与 external_task_id 两种查询方式二选一
external_task_id
string
可选
图生视频的自定义任务 ID

创建任务时填写的 external_task_id
与 task_id 两种查询方式二选一
cURL

复制

折叠
curl --request GET \
  --url https://api-beijing.klingai.com/v1/videos/image2video/{task_id} \
  --header 'Authorization: Bearer <token>'
200

复制

折叠
{
  "code": 0, // 错误码；具体定义见错误码
  "message": "string", // 错误信息
  "request_id": "string", // 请求ID，系统生成，用于跟踪请求、排查问题
  "data": {
    "task_id": "string", // 任务ID，系统生成
    "task_status": "string", // 任务状态，枚举值：submitted（已提交）、processing（处理中）、succeed（成功）、failed（失败）
    "task_status_msg": "string", // 任务状态信息，当任务失败时展示失败原因（如触发平台的内容风控等）
    "watermark_info": {
      "enabled": boolean
    },
    "task_result": {
      "videos": [
        {
          "id": "string", // 生成的视频ID；全局唯一
          "url": "string", // 生成视频的URL（请注意，为保障信息安全，生成的图片/视频会在30天后被清理，请及时转存）
          "watermark_url": "string", // 含水印视频下载URL，防盗链格式
          "duration": "string" // 视频总时长，单位s
        }
      ]
    },
    "task_info": { // 任务创建时的参数信息
      "external_task_id": "string" // 客户自定义任务ID
    },
    "final_unit_deduction": "string", // 任务最终扣减积分数值
    "created_at": 1722769557708, // 任务创建时间，Unix时间戳、单位ms
    "updated_at": 1722769557708 // 任务更新时间，Unix时间戳、单位ms
  }
}
查询任务（列表）
GET
/v1/videos/image2video
请求头
Content-Type
string
必填
默认值 application/json
数据交换格式

Authorization
string
必填
鉴权信息，参考接口鉴权

查询参数
pageNum
int
可选
默认值 1
页码

取值范围：[1, 1000]
pageSize
int
可选
默认值 30
每页数据量

取值范围：[1, 500]
cURL

复制

折叠
curl --request GET \
  --url 'https://api-beijing.klingai.com/v1/videos/image2video?pageNum=1&pageSize=30' \
  --header 'Authorization: Bearer <token>'
200

复制

折叠
{
  "code": 0, // 错误码；具体定义见错误码
  "message": "string", // 错误信息
  "request_id": "string", // 请求ID，系统生成，用于跟踪请求、排查问题
  "data": [
    {
      "task_id": "string", // 任务ID，系统生成
      "task_status": "string", // 任务状态，枚举值：submitted（已提交）、processing（处理中）、succeed（成功）、failed（失败）
      "task_status_msg": "string", // 任务状态信息，当任务失败时展示失败原因（如触发平台的内容风控等）
      "task_info": { // 任务创建时的参数信息
        "external_task_id": "string" // 客户自定义任务ID
      },
      "task_result": {
        "videos": [
          {
            "id": "string", // 生成的视频ID；全局唯一
            "url": "string", // 生成视频的URL（请注意，为保障信息安全，生成的图片/视频会在30天后被清理，请及时转存）
            "watermark_url": "string", // 含水印视频下载URL，防盗链格式
            "duration": "string" // 视频总时长，单位s
          }
        ]
      },
      "watermark_info": {
        "enabled": boolean
      },
      "final_unit_deduction": "string", // 任务最终扣减积分数值
      "created_at": 1722769557708, // 任务创建时间，Unix时间戳、单位ms
      "updated_at": 1722769557708 // 任务更新时间，Unix时间戳、单位ms
    }
  ]
}
上一篇：文生视频
下一篇：多图参考生视频