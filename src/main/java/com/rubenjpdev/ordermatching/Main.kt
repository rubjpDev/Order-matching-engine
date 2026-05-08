package main.java.com.rubenjpdev.ordermatching

import main.java.com.rubenjpdev.ordermatching.engine.OrderBook
import main.java.com.rubenjpdev.ordermatching.model.Order
import main.java.com.rubenjpdev.ordermatching.model.Price
import main.java.com.rubenjpdev.ordermatching.model.Quantity
import main.java.com.rubenjpdev.ordermatching.model.Side

fun main() {
    println("=== STARTING MATCHING ENGINE (Asset: AAPL) ===")
    val orderBook = OrderBook("AAPL")

    // Initial passive orders (market makers)
    println("\n[1] Adding passive liquidity...")

    // Sellers
    orderBook.addOrder(Order(id = 1, asset = "AAPL", side = Side.ASK, priceCents = Price(102), quantity = Quantity(100)))
    orderBook.addOrder(Order(id = 2, asset = "AAPL", side = Side.ASK, priceCents = Price(101), quantity = Quantity(50)))

    // Buyers
    orderBook.addOrder(Order(id = 3, asset = "AAPL", side = Side.BID, priceCents = Price(98), quantity = Quantity(200)))
    orderBook.addOrder(Order(id = 4, asset = "AAPL", side = Side.BID, priceCents = Price(99), quantity = Quantity(75)))

    println("Current spread -> BID: ${orderBook.getBestBid()} | ASK: ${orderBook.getBestAsk()}")

    // Aggressive order: crosses the spread
    println("\n[2] Incoming aggressive order: BUY 60 @ 101")
    val aggressiveOrder = Order(id = 5, asset = "AAPL", side = Side.BID, priceCents = Price(101), quantity = Quantity(60))

    val trades = orderBook.process(aggressiveOrder)

    // Results
    println("\n[3] Cross results:")
    println("Trades generated: ${trades.size}")
    trades.forEach { trade ->
        println(" -> Executed: ${trade.executedQuantity.value} shares at ${trade.executionPrice.value}$")
    }

    println("\n[4] Book state after the cross:")
    println("Current spread -> BID: ${orderBook.getBestBid()} | ASK: ${orderBook.getBestAsk()}")

    // Check the partial fill
    val remainingVol = orderBook.bids[101L]?.totalVolume?.value
    println("Remaining volume at BID level 101: $remainingVol")
}