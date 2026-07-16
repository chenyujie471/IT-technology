火山豆包格式（doubao-seedance-2-0-260128）
创建视频（文生视频）
POST
https://ai-api.kkidc.com/api/v3/contents/generations/tasks
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Body 参数
application/json
必填
用于向视频生成API提交完整请求的数据结构
model
string
模型名称
必需
用于生成视频的AI模型
prompt
string
提示词
必需
描述期望生成视频内容的自然语言文本，指导模型生成符合语义的视频
generate_audio
boolean
生成音频
必需
默认值 true，控制生成的视频是否包含与画面同步的声音。
true：模型输出的视频包含同步音频。模型会基于文本提示词与视觉内容，自动生成与之匹配的人声、音效及背景音乐。建议将对话部分置于双引号内，以优化音频生成效果。例如：男人叫住女人说：“你记住，以后不可以用手指指月亮。”
false：模型输出的视频为无声视频。
ratio
string
画面比例
必需
视频比例：16:9，4:3，1:1，3:4，9:16，21:9，adaptive
duration
integer
视频时长
必需
视频时长，默认 5 秒，范围 4~15 秒
resolution
string
视频分辨率
必需
生成视频的分辨率，例如 480p
watermark
boolean
添加水印
必需
true：含水印 false：不含水印，默认值 false
示例
{
"model": "doubao-seedance-2-0-260128",
"prompt": "写实风格，晴朗的蓝天之下，一大片白色的雏菊花田，镜头逐渐拉近，最终定格在一朵雏菊花的特写上，花瓣上有几颗晶莹的露珠",
"generate_audio": true,
"ratio": "16:9",
"duration": 5,
"resolution": "480p",
"watermark": false
}
请求示例代码
Unirest
OkHttp
Unirest.setTimeouts(0, 0);
HttpResponse<String> response = Unirest.post("https://ai-api.kkidc.com/api/v3/contents/generations/tasks")
.header("Authorization", "Bearer <token>")
.header("Content-Type", "application/json")
.body("{\r\n    \"model\": \"doubao-seedance-2-0-260128\",\r\n    \"prompt\": \"写实风格，晴朗的蓝天之下，一大片白色的雏菊花田，镜头逐渐拉近，最终定格在一朵雏菊花的特写上，花瓣上有几颗晶莹的露珠\",\r\n    \"generate_audio\": true,\r\n    \"ratio\": \"16:9\",\r\n    \"duration\": 5,\r\n    \"resolution\": \"480p\",\r\n    \"watermark\": false\r\n}")
.asString();
返回响应
🟢200
成功
application/json
id
string
ID
必需
唯一标识符
task_id
string
任务ID
必需
关联的任务唯一标识符
object
string
对象类型
必需
所操作的对象类型，例如文件、模型等
model
string
模型名称
必需
使用的AI模型名称或标识
status
string
状态
必需
当前任务执行状态，如 pending、running、success、failed 等
progress
integer
进度
必需
任务完成百分比（0-100）或当前处理步数
created_at
integer
创建时间
必需
任务创建的时间戳（Unix 时间戳，单位：秒）
示例
{
"id": "cgt-20260227153643-mx6c7",
"task_id": "cgt-20260227153643-mx6c7",
"object": "video",
"model": "doubao-seedance-1-5-pro-251215",
"status": "",
"progress": 0,
"created_at": 1772177804
}
🟠429
限流
🟠400
请求有误


火山豆包格式（doubao-seedance-2-0-260128）
获取视频生成任务状态
GET
https://ai-api.kkidc.com/api/v3/contents/generations/tasks/{task_id}
status任务状态：
NOT_START 未开始
SUBMITTED 已提交
QUEUED 排队中
IN_PROGRESS 处理中
SUCCESS 成功
FAILURE 失败
UNKNOWN 未知
上游data.data.status任务状态：
queued：排队中。
running：任务运行中。
succeeded： 任务成功。
failed：任务失败。
expired：任务超时。
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Path 参数
task_id
string
任务 ID
必需
示例:
cgt-20260227154402-28rl4
请求示例代码
Unirest
OkHttp
Unirest.setTimeouts(0, 0);
HttpResponse<String> response = Unirest.get("https://ai-api.kkidc.com/api/v3/contents/generations/tasks/cgt-20260227154402-28rl4")
.header("Authorization", "Bearer <token>")
.asString();
返回响应
🟢200
成功
application/json
code
string
响应码
必需
API 响应的状态码，例如 200 表示成功
message
string
响应消息
必需
对响应结果的简要描述，如 'success' 或具体错误信息
data
object
响应数据
必需
实际返回的业务数据对象
task_id
string
任务ID
必需
唯一标识该生成任务的字符串 ID
action
string
操作类型
必需
本次请求执行的动作，如 'generate_video'
status
string
任务状态
必需
任务当前状态，如 'pending'、'running'、'succeeded'、'failed'
fail_reason
string
失败原因
必需
任务失败时的具体原因（若 status 为 failed）
submit_time
integer
提交时间
必需
任务提交时间戳（毫秒级 Unix 时间）
start_time
integer
开始时间
必需
任务实际开始执行的时间戳（毫秒级 Unix 时间）
finish_time
integer
完成时间
必需
任务结束执行的时间戳（毫秒级 Unix 时间），未完成时可能为空或 0
progress
string
进度
必需
任务执行进度，格式如 '50%' 或 'processing'
data
object
视频生成详情
必需
视频生成任务的具体结果数据
示例
{
"code": "success",
"message": "",
"data": {
"task_id": "cgt-20260227150701-bwgfp",
"action": "generate",
"status": "SUCCESS",
"fail_reason": "https://ark-content-generation-cn-beijing.tos-cn-beijing.volces.com/doubao-seedance-1-5-pro/02177217602829600000000000000000000ffffac191d3663440f.mp4?X-Tos-Algorithm=TOS4-HMAC-SHA256&X-Tos-Credential=AKLTYWJkZTExNjA1ZDUyNDc3YzhjNTM5OGIyNjBhNDcyOTQ%2F20260227%2Fcn-beijing%2Ftos%2Frequest&X-Tos-Date=20260227T070748Z&X-Tos-Expires=86400&X-Tos-Signature=b7383ffd086326f52796a04f522d035dde83088d48d73ea961284aa65fb80205&X-Tos-SignedHeaders=host",
"submit_time": 1772176021,
"start_time": 1772176028,
"finish_time": 1772176073,
"progress": "100%",
"data": {
"id": "cgt-20260227150701-bwgfp",
"seed": 22698,
"draft": false,
"model": "doubao-seedance-1-5-pro-251215",
"ratio": "1:1",
"usage": {
"total_tokens": 108900,
"completion_tokens": 108900
},
"status": "succeeded",
"content": {
"video_url": "https://ark-content-generation-cn-beijing.tos-cn-beijing.volces.com/doubao-seedance-1-5-pro/02177217602829600000000000000000000ffffac191d3663440f.mp4?X-Tos-Algorithm=TOS4-HMAC-SHA256&X-Tos-Credential=AKLTYWJkZTExNjA1ZDUyNDc3YzhjNTM5OGIyNjBhNDcyOTQ%2F20260227%2Fcn-beijing%2Ftos%2Frequest&X-Tos-Date=20260227T070748Z&X-Tos-Expires=86400&X-Tos-Signature=b7383ffd086326f52796a04f522d035dde83088d48d73ea961284aa65fb80205&X-Tos-SignedHeaders=host"
},
"duration": 5,
"created_at": 1772176022,
"resolution": "720p",
"updated_at": 1772176068,
"service_tier": "default",
"generate_audio": true,
"framespersecond": 24,
"execution_expires_after": 172800
}
}
}
🟠429
限流
🟠400
请求有误