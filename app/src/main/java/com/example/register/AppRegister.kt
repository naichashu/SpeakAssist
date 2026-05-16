package com.example.register

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.diagnostics.AppLog

object AppRegister {
    private const val TAG = "AppRegister"

    private var appToPackageMap: MutableMap<String, String> = mutableMapOf()

    private var packageToAppMap: MutableMap<String, String> = mutableMapOf()

    private var packageLaunchableMap: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * 别名映射表：把模型常输出的英文 / 拼音 / 品牌名翻成本机标签关键字。
     *
     * 背景：模型从训练数据学到 "哔哩哔哩 = bilibili"，思考用中文但 action 输出英文，
     * 而 PackageManager.loadLabel() 国行设备返回中文标签，
     * `"哔哩哔哩".contains("bilibili")` = false，导致已装的应用被报"未找到"。
     *
     * 用法：lookup 时先把 alias 翻成中文关键字，再用关键字去 appToPackageMap 做
     * 精确 / 模糊匹配。alias 全小写比较，避免大小写敏感。
     */
    private val ALIAS_MAP: Map<String, String> = mapOf(
        // === 视频 / 直播 ===
        "bilibili" to "哔哩哔哩",
        "b站" to "哔哩哔哩",
        "小破站" to "哔哩哔哩",
        "douyin" to "抖音",
        "tiktok" to "抖音",
        "kuaishou" to "快手",
        "xigua" to "西瓜视频",
        "youku" to "优酷",
        "iqiyi" to "爱奇艺",
        "qqlive" to "腾讯视频",
        "tencentvideo" to "腾讯视频",
        "mgtv" to "芒果TV",
        "mango" to "芒果TV",
        "youtube" to "YouTube",
        "netflix" to "Netflix",
        "huya" to "虎牙",
        "douyu" to "斗鱼",

        // === 社交 / 通讯 ===
        "wechat" to "微信",
        "weixin" to "微信",
        "qq" to "QQ",
        "tim" to "TIM",
        "weibo" to "微博",
        "xiaohongshu" to "小红书",
        "rednote" to "小红书",
        "redbook" to "小红书",
        "xhs" to "小红书",
        "douban" to "豆瓣",
        "zhihu" to "知乎",
        "tieba" to "贴吧",
        "baidutieba" to "贴吧",
        "twitter" to "Twitter",
        "telegram" to "Telegram",
        "whatsapp" to "WhatsApp",
        "line" to "LINE",
        "kakao" to "KakaoTalk",
        "discord" to "Discord",
        "instagram" to "Instagram",
        "facebook" to "Facebook",
        "messenger" to "Messenger",
        "snapchat" to "Snapchat",

        // === 购物 ===
        "taobao" to "淘宝",
        "tmall" to "天猫",
        "jd" to "京东",
        "jingdong" to "京东",
        "pinduoduo" to "拼多多",
        "pdd" to "拼多多",
        "vipshop" to "唯品会",
        "dewu" to "得物",
        "poizon" to "得物",
        "xianyu" to "闲鱼",
        "samsclub" to "山姆",
        "amazon" to "亚马逊",
        "ebay" to "eBay",
        "shein" to "SHEIN",
        "kaola" to "考拉",
        "suning" to "苏宁",
        "gome" to "国美",

        // === 生活 / 出行 ===
        "meituan" to "美团",
        "dianping" to "大众点评",
        "eleme" to "饿了么",
        "didi" to "滴滴",
        "didichuxing" to "滴滴",
        "uber" to "Uber",
        "amap" to "高德地图",
        "gaode" to "高德地图",
        "gaodemap" to "高德地图",
        "baidumap" to "百度地图",
        "tencentmap" to "腾讯地图",
        "google maps" to "Google地图",
        "googlemaps" to "Google地图",
        "ctrip" to "携程",
        "qunar" to "去哪儿",
        "fliggy" to "飞猪",
        "tongcheng" to "同程",
        "tongchenglvxing" to "同程",
        "12306" to "铁路12306",
        "umetrip" to "航旅纵横",
        "kfc" to "KFC",
        "mcdonalds" to "麦当劳",
        "starbucks" to "星巴克",
        "luckin" to "瑞幸咖啡",
        "luckincoffee" to "瑞幸咖啡",
        "heytea" to "喜茶",
        "naixue" to "奈雪",
        "cainiao" to "菜鸟",

        // === 金融 / 支付 ===
        "alipay" to "支付宝",
        "zhifubao" to "支付宝",
        "icbc" to "工商银行",
        "ccb" to "建设银行",
        "boc" to "中国银行",
        "abc" to "农业银行",
        "cmb" to "招商银行",
        "zhaoshang" to "招商银行",
        "cmbc" to "民生银行",
        "psbc" to "邮储银行",
        "bocom" to "交通银行",
        "tiantianjijin" to "天天基金",
        "lufax" to "陆金所",

        // === 工作 / 协作 ===
        "feishu" to "飞书",
        "lark" to "飞书",
        "dingtalk" to "钉钉",
        "dingding" to "钉钉",
        "wechatwork" to "企业微信",
        "wework" to "企业微信",
        "qiyeweixin" to "企业微信",
        "tencentmeeting" to "腾讯会议",
        "wemeet" to "腾讯会议",
        "voov" to "腾讯会议",
        "zoom" to "Zoom",
        "tencentdocs" to "腾讯文档",
        "shimo" to "石墨文档",
        "wpsoffice" to "WPS Office",
        "wps" to "WPS Office",
        "office" to "Office",
        "boss" to "BOSS直聘",
        "bosszhipin" to "BOSS直聘",
        "liepin" to "猎聘",
        "zhilian" to "智联招聘",
        "51job" to "前程无忧",
        "lagou" to "拉勾",
        "maimai" to "脉脉",
        "linkedin" to "领英",
        "github" to "GitHub",

        // === 音乐 ===
        "qqmusic" to "QQ音乐",
        "neteasemusic" to "网易云音乐",
        "netease" to "网易云音乐",
        "wangyiyun" to "网易云音乐",
        "163music" to "网易云音乐",
        "kugou" to "酷狗音乐",
        "kuwo" to "酷我音乐",
        "spotify" to "Spotify",
        "applemusic" to "Apple Music",
        "ximalaya" to "喜马拉雅",
        "qingting" to "蜻蜓FM",
        "lizhi" to "荔枝",

        // === 阅读 / 漫画 ===
        "kindle" to "Kindle",
        "weread" to "微信读书",
        "weixinreading" to "微信读书",
        "qidian" to "起点读书",
        "qidianreader" to "起点读书",
        "ireader" to "掌阅",
        "qqreader" to "QQ阅读",
        "kuaikan" to "快看漫画",
        "manhuadao" to "漫画岛",

        // === 浏览器 ===
        "quark" to "夸克",
        "quarkbrowser" to "夸克",
        "uc" to "UC浏览器",
        "ucbrowser" to "UC浏览器",
        "qqbrowser" to "QQ浏览器",
        "chrome" to "Chrome",
        "firefox" to "火狐",
        "edge" to "Edge",
        "safari" to "Safari",

        // === 网盘 / 文件 ===
        "baidunetdisk" to "百度网盘",
        "baiduwangpan" to "百度网盘",
        "baidudisk" to "百度网盘",
        "weiyun" to "微云",
        "aliyunpan" to "阿里云盘",
        "alipan" to "阿里云盘",
        "tianyiyun" to "天翼云盘",
        "115" to "115",
        "onedrive" to "OneDrive",
        "googledrive" to "Google Drive",
        "dropbox" to "Dropbox",

        // === 学习 / 教育 ===
        "duolingo" to "多邻国",
        "tencentclass" to "腾讯课堂",
        "xueersi" to "学而思",
        "chaoxing" to "学习通",
        "xuexitong" to "学习通",
        "xuexiqiangguo" to "学习强国",
        "youdao" to "网易有道",
        "youdaocidian" to "有道词典",
        "youdaodict" to "有道词典",
        "shanbay" to "扇贝",
        "baicizhan" to "百词斩",
        "mojidict" to "MOJi辞書",
        "knowledgeplanet" to "知识星球",
        "zhishixingqiu" to "知识星球",
        "icourse" to "中国大学MOOC",
        "mooc" to "中国大学MOOC",

        // === 健康 / 运动 ===
        "keep" to "Keep",
        "huaweihealth" to "华为运动健康",
        "mihealth" to "小米运动健康",
        "yundongbu" to "悦动圈",
        "ledongli" to "乐动力",

        // === 摄影 / 修图 ===
        "meitu" to "美图秀秀",
        "meituxiuxiu" to "美图秀秀",
        "vsco" to "VSCO",
        "snapseed" to "Snapseed",
        "lightroom" to "Lightroom",
        "picsart" to "PicsArt",
        "faceu" to "Faceu激萌",
        "b612" to "B612",
        "huangyou" to "黄油相机",

        // === 工具 / 系统 ===
        "calculator" to "计算器",
        "jisuanqi" to "计算器",
        "calendar" to "日历",
        "rili" to "日历",
        "clock" to "时钟",
        "shizhong" to "时钟",
        "alarm" to "时钟",
        "alarmclock" to "时钟",
        "camera" to "相机",
        "xiangji" to "相机",
        "gallery" to "相册",
        "photos" to "相册",
        "xiangce" to "相册",
        "contacts" to "联系人",
        "lianxiren" to "联系人",
        "tongxunlu" to "通讯录",
        "phone" to "电话",
        "dianhua" to "电话",
        "dialer" to "电话",
        "messages" to "信息",
        "messaging" to "短信",
        "sms" to "短信",
        "duanxin" to "短信",
        "notes" to "便签",
        "memo" to "便签",
        "biaoji" to "便签",
        "bianqian" to "便签",
        "filemanager" to "文件管理",
        "wenjianguanli" to "文件管理",
        "files" to "文件",
        "wenjian" to "文件",
        "browser" to "浏览器",
        "liulanqi" to "浏览器",
        "settings" to "设置",
        "setting" to "设置",
        "shezhi" to "设置",
        "system" to "设置",
        "appstore" to "应用商店",
        "yingyongshangdian" to "应用商店",
        "appmarket" to "应用市场",
        "googleplay" to "Google Play",
        "playstore" to "Google Play",
        "camscanner" to "扫描全能王",
        "scanner" to "扫描全能王",
        "compass" to "指南针",
        "weather" to "天气",
        "tianqi" to "天气",
        "recorder" to "录音机",
        "luyinji" to "录音机",
        "flashlight" to "手电筒",
        "shoudiantong" to "手电筒",

        // === 搜索 / 问答 ===
        "baidu" to "百度",
        "baidusearch" to "百度",
        "google" to "Google",
        "googlesearch" to "Google",
        "bing" to "必应",
        "sogou" to "搜狗",
        "360search" to "360搜索",

        // === 邮箱 ===
        "gmail" to "Gmail",
        "qqmail" to "QQ邮箱",
        "163mail" to "网易邮箱",
        "neteasemail" to "网易邮箱",
        "outlook" to "Outlook",

        // === AI / 大模型 ===
        "doubao" to "豆包",
        "kimi" to "Kimi",
        "wenxin" to "文心一言",
        "wenxinyiyan" to "文心一言",
        "tongyi" to "通义",
        "deepseek" to "DeepSeek",
        "chatgpt" to "ChatGPT",
        "gemini" to "Gemini",
        "claude" to "Claude",
        "grok" to "Grok",
        "perplexity" to "Perplexity",
        "midjourney" to "Midjourney",

        // === 政务 / 公共 ===
        "individualtax" to "个人所得税",
        "tax" to "个人所得税",
        "geshui" to "个人所得税",
        "antifraud" to "国家反诈",
        "guojiafanzha" to "国家反诈",
        "fanzha" to "国家反诈",
        "chsi" to "学信网",
        "xuexin" to "学信网",
        "jiaoguan12123" to "交管12123",
        "12123" to "交管12123",
        "jiankangbao" to "健康宝",
        "yikatong" to "一卡通",

        // === 游戏 ===
        "wzry" to "王者荣耀",
        "honorofkings" to "王者荣耀",
        "kingofglory" to "王者荣耀",
        "pubg" to "和平精英",
        "peacekeeperelite" to "和平精英",
        "yuanshen" to "原神",
        "genshin" to "原神",
        "genshinimpact" to "原神",
        "hsr" to "崩坏：星穹铁道",
        "starrail" to "崩坏：星穹铁道",
        "minecraft" to "我的世界",
        "wodeshijie" to "我的世界",
        "lol" to "英雄联盟手游",

        // === 二手 / 招聘 ===
        "guazi" to "瓜子二手车",
        "renren" to "人人车",

        // === 其他 ===
        "twitch" to "Twitch",
        "reddit" to "Reddit",
        "pinterest" to "Pinterest",
        "evernote" to "印象笔记",
        "yinxiangbiji" to "印象笔记",
        "notion" to "Notion",
        "obsidian" to "Obsidian",
    )

    fun getPackageName(appName: String): String {
        val name = appName.trim()
        if (name.isEmpty()) return ""

        // 1. 本机标签精确命中
        appToPackageMap[name]?.let { return it }

        // 2. 别名翻译：把英文/拼音/品牌名映射到中文关键字，再去精确+模糊查
        //    解决"模型说 bilibili，loadLabel 返回 哔哩哔哩"这种语言不一致
        val aliasKey = ALIAS_MAP[name.lowercase()]
        if (aliasKey != null) {
            appToPackageMap[aliasKey]?.let {
                AppLog.d(TAG, "别名命中: $name -> $aliasKey -> $it")
                return it
            }
            appToPackageMap.entries.firstOrNull { (label, _) ->
                label.contains(aliasKey, ignoreCase = true)
            }?.let {
                AppLog.d(TAG, "别名模糊命中: $name -> $aliasKey -> ${it.key} -> ${it.value}")
                return it.value
            }
        }

        // 3. 原名直接子串模糊匹配（兜底，保留旧行为）
        appToPackageMap.entries.firstOrNull { (label, _) ->
            label.contains(name, ignoreCase = true)
        }?.let {
            AppLog.d(TAG, "模糊命中: $name -> ${it.key} -> ${it.value}")
            return it.value
        }

        AppLog.w(TAG, "未找到 app: $name")
        return ""
    }

    /**
     * 获取所有已安装 App 的「应用名称→包名」可变映射
     * @param context 上下文（Activity/Service/Application 均可）
     * @return MutableMap<String, String> 键：应用名称，值：App 唯一包名
     */
    fun initialize(context: Context){
        // 清空旧映射，避免卸载的应用残留、避免重复条目
        appToPackageMap.clear()
        packageToAppMap.clear()
        packageLaunchableMap.clear()

        // 初始化需要的可变映射（满足你的数据结构要求）
        val packageManager: PackageManager = context.packageManager

        // 1. 获取所有已安装 App 的信息集合
        val installedApplications = packageManager.getInstalledApplications(
            PackageManager.GET_META_DATA // 仅获取基础元数据，提升效率
        )

        AppLog.d(TAG, "已安装 App 数量：${installedApplications.size}")

        // 2. 遍历，提取「应用名称」和「包名」，存入可变映射
        for (appInfo: ApplicationInfo in installedApplications) {
            try {
                // 获取应用名称（用户可见，如「微信」「设置」）
                val appName = appInfo.loadLabel(packageManager).toString()
                // 获取 App 唯一包名（如「com.tencent.mm」）
                val packageName = appInfo.packageName
                val isLaunchable = packageManager.getLaunchIntentForPackage(packageName) != null

                // HarmonyOS may expose atomic-service companion packages with the same label
                // but no launcher entry, for example com.harmony.meituan. Keep the launchable app.
                val existingPackageName = appToPackageMap[appName]
                val existingLaunchable = existingPackageName?.let {
                    packageLaunchableMap[it] == true
                } == true
                if (existingPackageName == null || isLaunchable || !existingLaunchable) {
                    appToPackageMap[appName] = packageName
                }

                packageToAppMap[packageName] = appName
                packageLaunchableMap[packageName] = isLaunchable
            } catch (e: Exception) {
                // 极少数系统 App 可能获取失败，直接跳过
                continue
            }
        }
    }
}
