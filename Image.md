生成图像（gpt-image-2）
POST
https://ai-api.kkidc.com/v1/images/generations
OpenAI格式进行请求
优先使用流式请求
图片生成较慢，超时时间建议设置600秒
使用代码程序调用，不要直接apifox调试（apifox有限制超时会主动断开）
可选模型ID例如：
gpt-image-2
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Body 参数
application/json
必填
model
string
模型名称
必需
要使用的图像生成模型名称
prompt
string
提示词
必需
用于生成图像的文本描述，需清晰具体
size
string
图像尺寸
必需
生成图像的尺寸，例如auto，1024x1024，1536x1024，1024x1536，2048x2048(2K)，2048x1152(2K)，3840x2160(4K)，2160x3840(4K)
n
integer
生成数量
必需
生成图像的数量（默认为1），仅支持1张
quality
string
图像质量
必需
图像质量等级，如 'auto'，'low' ，'medium'，'high'
response_format
string
响应格式
必需
返回结果的格式，固定 'b64_json'
stream
string
请求方式
可选
是否流式请求，true或false，默认false
示例
{
"model": "gpt-image-2",
"prompt": "masterpiece, best quality, ultra detailed, anime style, young cute girl, soft lighting, smooth skin, big expressive eyes,long hair, delicate face, small nose, gentle smile,dynamic pose, looking at viewer",
"size": "1024x1024",
"n": 1,
"response_format": "b64_json"
}
请求示例代码
Unirest
OkHttp
Unirest.setTimeouts(0, 0);
HttpResponse<String> response = Unirest.post("https://ai-api.kkidc.com/v1/images/generations")
.header("Authorization", "Bearer <token>")
.header("Content-Type", "application/json")
.body("{\r\n    \"model\": \"gpt-image-2\",\r\n    \"prompt\": \"masterpiece, best quality, ultra detailed, anime style, young cute girl, soft lighting, smooth skin, big expressive eyes,long hair, delicate face, small nose, gentle smile,dynamic pose, looking at viewer\",\r\n    \"size\": \"1024x1024\",\r\n    \"n\": 1,\r\n    \"response_format\": \"b64_json\"\r\n}")
.asString();
返回响应
🟢200
成功
application/json
data
array[object (图像数据项)]
生成的图像数据列表
可选
url
string
图像 URL
可选
图像的 URL 地址
b64_json
string
Base64 图像数据
可选
图像的 Base64 编码字符串
revised_prompt
string
优化后的提示词
可选
系统根据原始提示词优化后的提示词
示例
{
"data": [
{
"url": "",
"b64_json": "base64图片",
"revised_prompt": ""
}
],
"created": 1776824208
}
🟠429
限流
🟠400
请求有误