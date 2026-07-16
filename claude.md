文本对话（优先）
POST
https://ai-api.kkidc.com/v1/messages
官方格式请求
可选模型ID例如：
claude-haiku-4-5-20251001
claude-sonnet-4-6
claude-opus-4-6
claude-opus-4-7等
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Body 参数
application/json
必填
用于发送聊天请求的数据模型，包含模型选择、消息历史和生成限制
model
string
模型名称
必需
要使用的AI模型标识符，对应官方的模型ID
messages
array [object]
消息列表
必需
包含对话历史的消息数组，每条消息包含角色和内容
role
string
消息角色
可选
消息发送者的角色，如user（用户）、assistant（助手）或system（系统）
content
string
消息内容
可选
消息的具体文本内容
max_tokens
integer
最大令牌数
可选
聊天完成时生成的最大令牌数。输入标记和生成标记的总长度受模型上下文长度的限制。
示例
{
"model": "claude-sonnet-4-6",
"messages": [
{
"role": "user",
"content": "Hello"
}
],
"max_tokens": 500
}