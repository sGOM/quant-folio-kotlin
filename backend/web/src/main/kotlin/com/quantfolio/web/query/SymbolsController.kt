package com.quantfolio.web.query

import com.quantfolio.domain.user.User
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.market.symbols.SymbolInfo
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/symbols")
class SymbolsController(private val catalog: SymbolCatalog) {
    @GetMapping("/search")
    fun search(
        @CurrentUser user: User,
        @RequestParam @Size(min = 1, max = 40) q: String,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ): List<SymbolInfo> = catalog.search(q, limit)

    @GetMapping("/names")
    fun names(@CurrentUser user: User): Map<String, String> = catalog.nameMap()
}
