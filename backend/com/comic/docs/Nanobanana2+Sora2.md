### Nanobanana2

接口地址： https://api.wuyinkeji.com/api/async/image_nanoBanana2 

返回格式： application/json

请求方式： HTTPPOST

请求示例： https://api.wuyinkeji.com/api/async/image_nanoBanana2?key=你的密钥

请求HEADER：
名称	值
Authorization	接口密钥,在控制台->密钥管理查看
Content-Type	application/json
请求参数说明：
名称	必填	类型	示例值	说明
prompt	是	string	
提示词
size	否	string	1K	
输出图像大小,支持的大小:
1K
2K
4K
默认 1K

注意：分辨率越高，生成时间越长

aspectRatio	否	string	auto	输出图像比例,支持的比例:
auto
1:1
16:9
9:16
4:3
3:4
3:2
2:3
5:4
4:5
21:9
默认 auto
urls	否	array	["xxx.jpg","xxx.jpg"]	参考图URL or Base64
返回参数说明：
名称	类型	说明
code	int	状态码
msg	string	状态信息
data	string	请求结果数据集
data.id	string	请求结果id
exec_time	float	执行耗时
user_ip	string	客户端IP
返回示例：
{
  "code": 200,
  "msg": "成功",
  "data": {
    "id": "image_4d39239e-776a-4cbd-a8eb-e2d9b4816829",
    "count": 10
  },
  "exec_time": 0.290186,
  "ip": "119.6.176.239"
}


### Sora2

接口地址： https://api.wuyinkeji.com/api/async/video_sora2 

返回格式： application/json

请求方式： HTTPPOST

请求示例： https://api.wuyinkeji.com/api/async/video_sora2?key=你的密钥

请求HEADER：
名称	值
Authorization	接口密钥,在控制台->密钥管理查看
Content-Type	application/json
请求参数说明：
名称	必填	类型	示例值	说明
prompt	是	string	
提示词
aspectRatio	否	string	9:16	
输出视频比例,支持的比例:9:16 16:9 默认 9:16

url	否	


string



xx.jpg	参考图片的URL
duration	否	string	15	视频时长(秒): 10, 15, 默认10
size	否	string	small	视频清晰度: small, large, 默认small
remixTargetId	否	string	
续作PID，续作视频所返回的PID
返回参数说明：
名称	类型	说明
code	int	状态码
msg	string	状态信息
data	string	请求结果数据集
data.id	string	请求结果id
exec_time	float	执行耗时
user_ip	string	客户端IP
返回示例：
{
  "code": 200,
  "msg": "成功",
  "data": {
    "id": "video_4d39239e-776a-4cbd-a8eb-e2d9b4816829",
    "count": 10
  },
  "exec_time": 0.290186,
  "ip": "119.6.176.239"
}