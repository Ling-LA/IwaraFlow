package com.ling.iwaraflow

import android.content.Context
import android.net.ConnectivityManager

/**
 * 弱网/计量网络下少抢带宽：预缓存条数和冷启动预检窗口都跟着网络走。
 * 画质不受影响——这里只决定“提前下多少还没看的东西”。
 */
object NetworkProfile {
    /** 低于这个下行估计就按弱网处理（原画大概需要 4 Mbps 才能边下边看）。 */
    const val SLOW_LINK_KBPS = 4_000

    /** 宽裕网络预缓存下一条和下下条，省流量时只预缓存下一条。 */
    fun prefetchCount(metered: Boolean, downstreamKbps: Int): Int =
        if (metered || (downstreamKbps in 1 until SLOW_LINK_KBPS)) 1 else 2

    /** 冷启动预检的候选窗口：弱网上每个候选都是额外的往返，先少验几条把首屏放出来。 */
    fun coldStartCandidates(metered: Boolean, downstreamKbps: Int): Int =
        if (metered || (downstreamKbps in 1 until SLOW_LINK_KBPS)) FRUGAL_COLD_START_CANDIDATES
        else PlayableVideoGate.COLD_START_CANDIDATES

    const val FRUGAL_COLD_START_CANDIDATES = 6

    fun prefetchCount(context: Context): Int = prefetchCount(isMetered(context), downstreamKbps(context))

    fun coldStartCandidates(context: Context): Int =
        coldStartCandidates(isMetered(context), downstreamKbps(context))

    private fun isMetered(context: Context): Boolean = runCatching {
        manager(context)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)

    /** 系统给的下行带宽估计，拿不到时返回 0，按“未知即宽裕”处理。 */
    private fun downstreamKbps(context: Context): Int = runCatching {
        val connectivity = manager(context) ?: return 0
        val network = connectivity.activeNetwork ?: return 0
        connectivity.getNetworkCapabilities(network)?.linkDownstreamBandwidthKbps ?: 0
    }.getOrDefault(0)

    private fun manager(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}
