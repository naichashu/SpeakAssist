package com.example.register

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

object AppRegister {
    private const val TAG = "AppRegister"

    private var appToPackageMap: MutableMap<String, String> = mutableMapOf()

    private val packageToAppMap: MutableMap<String, String> = mutableMapOf()

    fun getPackageName(appName: String): String {
        val name = appName.trim()
        if (appToPackageMap[name] != null) return appToPackageMap[name]!!
        appToPackageMap.entries.firstOrNull { (temp, _) ->
            temp.contains(name, ignoreCase = true)
        }?.let { return it.value }
        return ""
    }

    /**
     * 获取所有已安装 App 的「应用名称→包名」可变映射
     * @param context 上下文（Activity/Service/Application 均可）
     * @return MutableMap<String, String> 键：应用名称，值：App 唯一包名
     */
    // TODO 未初始化
    fun initialize(context: Context){
        // 初始化需要的可变映射（满足你的数据结构要求）
        val packageManager: PackageManager = context.packageManager

        // 1. 获取所有已安装 App 的信息集合
        val installedApplications = packageManager.getInstalledApplications(
            PackageManager.GET_META_DATA // 仅获取基础元数据，提升效率
        )

        Log.d(TAG, "已安装 App 数量：${installedApplications.size}")

        // 2. 遍历，提取「应用名称」和「包名」，存入可变映射
        for (appInfo: ApplicationInfo in installedApplications) {
            try {
                // 获取应用名称（用户可见，如「微信」「设置」）
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                // 获取 App 唯一包名（如「com.tencent.mm」）
                val packageName = appInfo.packageName

                // 存入映射（键：应用名称，值：包名）
                appToPackageMap[appName] = packageName
                appToPackageMap[appName.lowercase()] = packageName

                packageToAppMap[packageName] = appName
            } catch (e: Exception) {
                // 极少数系统 App 可能获取失败，直接跳过
                continue
            }
        }

    }
}