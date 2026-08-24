火山豆包格式（seed-2.0）
创建视频（多模态-参考生视频）
POST
/v1/video/generations
注意：涉及人脸，虚拟人图片需走素材库接口过审，否则视频会生成失败
模型ID：
doubao-seedance-2-0-260128
doubao-seedance-2-0-fast-260128
请求参数
Authorization
在 Header 添加参数 Authorization，其值为在 Bearer 之后拼接 Token
示例：
Authorization: Bearer ********************
Body 参数
application/json
必填
完整的多模态生成请求结构
model
string
模型名称
必需
指定要使用的AI模型名称，例如 gpt-4、claude-3 等
prompt
string
提示词
必需
用于引导模型生成内容的自然语言指令或问题
metadata
object
元数据
必需
控制生成行为的高级参数集合
content
array [object]
输入内容列表
必需
多模态输入内容数组，支持图片、视频、音频和文本（通过 prompt 字段）
generate_audio
boolean
生成音频
必需
是否启用音频合成输出（如TTS）
ratio
string
宽高比
必需
生成图像或视频的宽高比，例如 16:9、4:3、1:1 等
duration
integer
持续时间
必需
生成视频或音频的时长（单位：秒）
watermark
boolean
添加水印
必需
是否在生成结果上叠加平台水印
示例
{
"model": "doubao-seedance-2-0-fast-260128",
"prompt": "全程使用视频1的第一视角构图，全程使用音频1作为背景音乐。第一人称视角果茶宣传广告，seedance牌「苹苹安安」苹果果茶限定款；首帧为图片1，你的手摘下一颗带晨露的阿克苏红苹果，轻脆的苹果碰撞声；2-4 秒：快速切镜，你的手将苹果块投入雪克杯，加入冰块与茶底，用力摇晃，冰块碰撞声与摇晃声卡点轻快鼓点，背景音：「鲜切现摇」；4-6 秒：第一人称成品特写，分层果茶倒入透明杯，你的手轻挤奶盖在顶部铺展，在杯身贴上粉红包标，镜头拉近看奶盖与果茶的分层纹理；6-8 秒：第一人称手持举杯，你将图片2中的果茶举到镜头前（模拟递到观众面前的视角），杯身标签清晰可见，背景音「来一口鲜爽」，尾帧定格为图片2。背景声音统一为女生音色。",
"metadata": {
"content": [
{
"type": "image_url",
"image_url": {
"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/r2v_tea_pic1.jpg"
},
"role": "reference_image"
},
{
"type": "image_url",
"image_url": {
"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/r2v_tea_pic2.jpg"
},
"role": "reference_image"
},
{
"type": "video_url",
"video_url": {
"url": "https://ark-project.tos-cn-beijing.volces.com/doc_video/r2v_tea_video1.mp4"
},
"role": "reference_video"
},
{
"type": "audio_url",
"audio_url": {
"url": "https://ark-project.tos-cn-beijing.volces.com/doc_audio/r2v_tea_audio1.mp3"
},
"role": "reference_audio"
}
],
"generate_audio": true,
"ratio": "16:9",
"duration": 10,
"watermark": false
}
}
请求示例代码
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