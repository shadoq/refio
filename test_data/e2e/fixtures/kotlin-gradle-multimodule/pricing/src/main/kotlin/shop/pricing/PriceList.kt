package shop.pricing

import shop.money.Money

class UnknownSkuException(sku: String) : RuntimeException("Unknown sku: $sku")

class PriceList(private val prices: Map<String, Money>) {
    fun unitPrice(sku: String): Money = prices[sku] ?: throw UnknownSkuException(sku)
}
