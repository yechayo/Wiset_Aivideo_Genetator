**接口地址：** [https://api.wuyinkeji.com/api/async/video\_grok\_imagine](https://api.wuyinkeji.com/api/async/video_grok_imagine)

**返回格式：** application/json

**请求方式：** **HTTP**POST

**请求示例：** `https://api.wuyinkeji.com/api/async/video_grok_imagine?key=你的密钥`

## 请求HEADER：


| 名称          | 值                              |
| ------------- | ------------------------------- |
| Authorization | 接口密钥,在控制台->密钥管理查看 |
| Content-Type  | application/json                |

## 请求参数说明：


| 名称          | 必填 | 类型   | 示例值                                | 说明                                                                                                                 |
| ------------- | ---- | ------ | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| prompt        | 是   | string | 帮我生成一个小猫钓鱼的视频            | 提示词                                                                                                               |
| duration      | 否   | string | 10                                    | 生成视频的持续时间（秒）可用选项:6,10,15                                                                             |
| aspect\_ratio | 否   | string | 2:3<br/>                              | 指定生成视频的宽高比。控制输出的宽高比。2:3: 竖向（垂直）3:2: 横向（水平）1:1: 正方形16:9: 宽屏9:16: 竖屏默认值：2:3 |
| image\_urls   | 否   | array  | ["https://cdn.dramastudio.ai/dc.jpg"] | 参考图，数组格式，页面请求用json格式，有参考图的情况下，宽高比不生效                                                 |

## 返回参数说明：


| 名称       | 类型   | 说明           |
| ---------- | ------ | -------------- |
| code       | int    | 状态码         |
| msg        | string | 状态信息       |
| data       | string | 请求结果数据集 |
| exec\_time | float  | 执行耗时       |
| user\_ip   | string | 客户端IP       |

#### 返回示例：

{

"code": 200,

"msg": "成功",

"data": {

"id": "video\_4d39239e-776a-4cbd-a8eb-e2d9b4816829",

"count": null
},

"exec\_time": 1.218999,

"ip": "175.152.149.53"

}
