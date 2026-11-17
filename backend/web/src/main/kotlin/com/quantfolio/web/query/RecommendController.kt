package com.quantfolio.web.query

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.user.User
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.metrics.RecommendOut
import com.quantfolio.metrics.RecommendService
import com.quantfolio.web.api.JsonCache
import com.quantfolio.web.auth.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RecommendController(private val common: MetricsCommon, private val cache: JsonCache, private val recommend: RecommendService) {
    @GetMapping("/api/recommend/kospi200")
    fun kospi200(@CurrentUser user: User): RecommendOut {
        val asOf = common.lastBusinessDay()
        return cache.getOrCompute(RedisKeys.recommendCache(asOf), RecommendOut::class.java, { it.items.isNotEmpty() }) { recommend.kospi200Scored(asOf) }
    }
}
