视频素材相关接口
上传素材
POST
https://ai-api.kkidc.com/v1/assets/upload
把素材 URL 提交到素材库，返回素材 ID。支持单个 URL 或一次批量多个 URL。
URL 要求
必须是公网可访问的 http(s) 地址，不支持 localhost、内网/回环 IP、本地文件。
扩展名须在支持范围内（须与 type 匹配）：
图片：jpeg / jpg / png / webp / bmp / tiff / gif / heic
视频：mp4 / mov
音频：wav / mp3
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Body 参数
application/json
必填
url 与 urls 二选一；同时提供时以 urls 为准。
url
string
必需
单个素材的公网 URL
urls
array[string]
必需
批量素材 URL 列表，单次最多 10 条
purpose
string
必需
素材用途/描述（缺失返回 400 purpose is required）
type
string
可选
素材类型：image / video / audio，默认 image。注意：素材是什么类型，就会校验什么类型的后缀。
group_id
string
可选
分组id（可不传）。不传时使用自动使用该用户已有分组，用户初始无分组时将自动为用户创建分组
示例
请求示例（单个）
请求示例（多个）
{
"url": "https://cdn.example.com/portrait.jpg",
"type": "image",
"purpose": "数字人形象"
}
请求示例代码
请求示例（单个）
请求示例（多个）
Unirest.setTimeouts(0, 0);
HttpResponse<String> response = Unirest.post("https://ai-api.kkidc.com/v1/assets/upload")
.header("Authorization", "Bearer <token>")
.header("Content-Type", "application/json")
.body("{\r\n    \"url\": \"https://cdn.example.com/portrait.jpg\",\r\n    \"type\": \"image\",\r\n    \"purpose\": \"数字人形象\"\r\n}")
.asString();
返回响应
🟢200
成功
application/json
data
object
必需
asset_id
string
素材 ID
必需
group_id
string
分组 ID
必需
status
string
素材状态
必需
message
string
必需
success
boolean
必需
示例
响应示例（单个）
响应示例（批量）
审核不通过
{
"data": {
"asset_id": "mat_ADyx4iQvkHUy7jDzPKqp",
"group_id": "ak-*******",
"status": "ready"
},
"message": "",
"success": true
}


视频素材相关接口
查询素材列表
GET
https://ai-api.kkidc.com/v1/assets
分页返回当前 Key 下**已就绪（可用于生成视频）**的素材。
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Query 参数
page
integer
页码，默认 1
可选
示例:
1
page_size
integer
每页条数
可选
示例:
20
purpose
string
模糊匹配
可选
示例:
素材描述
sort_order
string
Desc,Asc
可选
示例:
Asc
group_id
string
可选
分组id（可不传， 不传时查询用户的全部素材）
示例:
ak-8888888888888888888
请求示例代码
Unirest
OkHttp
Unirest.setTimeouts(0, 0);
HttpResponse<String> response = Unirest.get("https://ai-api.kkidc.com/v1/assets?page=1&page_size=20&purpose=%E7%B4%A0%E6%9D%90%E6%8F%8F%E8%BF%B0&sort_order=Asc&group_id=ak-8888888888888888888")
.header("Authorization", "Bearer <token>")
.asString();
返回响应
🟢200
成功
application/json
data
object
必需
page
integer
必需
page_size
integer
必需
total
integer
必需
items
array [object]
素材组
必需
message
string
必需
success
boolean
必需
示例
说明：列表只包含 ready 的素材。pending / failed 的素材请用「查询单个素材」接口按 asset_id 查看。
{
"data": {
"total": 1,
"page": 1,
"page_size": 10,
"items": [
{
"asset_id": "mat_ADyx4iQvkHUy7jDzPKqp",
"created_at": 1784686780,
"group_id": "ak-*******",
"original_url": "https://cdn.example.com/portrait.jpg",
"purpose": "测试",
"status": "ready",
"type": "image"
}
]
},
"message": "",
"success": true
}

视频素材相关接口
查询单个素材
GET
https://ai-api.kkidc.com/v1/assets/{asset_id}
按素材 ID 查询聚合状态。
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Path 参数
asset_id
string
必需
示例:
mat_888888888888888
Query 参数
group_id
string
分组id（可不传）
可选
示例:
ak-8888888888888888888
请求示例代码
Unirest
OkHttp
Unirest.setTimeouts(0, 0);
HttpResponse<String> response = Unirest.get("https://ai-api.kkidc.com/v1/assets/mat_888888888888888?group_id=ak-8888888888888888888")
.header("Authorization", "Bearer <token>")
.asString();
返回响应
🟢200
成功
application/json
data
object
必需
asset_id
string
素材id
必需
created_at
integer
创建时间
必需
group_id
string
分组id
必需
original_url
string
源URL
必需
purpose
string
用途描述
必需
status
string
状态
必需
type
string
类型
必需
message
string
必需
success
boolean
必需
示例
响应示例
审核不通过
{
"data": {
"asset_id": "mat_ADyx4iQvkHUy7jDzPKqp",
"created_at": 1784686780,
"group_id": "ak-********",
"original_url": "https://cdn.example.com/portrait.jpg",
"purpose": "测试",
"status": "ready",
"type": "image"
},
"message": "",
"success": true
}