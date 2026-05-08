package main.java.com.rubenjpdev.ordermatching.engine

import main.java.com.rubenjpdev.ordermatching.model.Order
import main.java.com.rubenjpdev.ordermatching.model.Price
import main.java.com.rubenjpdev.ordermatching.model.Quantity
import main.java.com.rubenjpdev.ordermatching.model.Side
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// OrderBook tests grouped by behaviour.
@DisplayName("OrderBook")
class OrderBookTest {

    // Helpers

    private lateinit var book: OrderBook

    private fun bid(id: Long, priceCents: Long, qty: Long, ts: Long = id) =
        Order(id, "AAPL", Side.BID, Price(priceCents), Quantity(qty), timestamp = ts)

    private fun ask(id: Long, priceCents: Long, qty: Long, ts: Long = id) =
        Order(id, "AAPL", Side.ASK, Price(priceCents), Quantity(qty), timestamp = ts)

    @BeforeEach
    fun setUp() {
        book = OrderBook("AAPL")
    }


    @Nested
    @DisplayName("Passive order placement")
    inner class PassivePlacement {

        @Test
        @DisplayName("A non-crossing BID parks in the bids tree")
        fun `non-crossing bid parks in bids tree`() {
            book.addOrder(ask(id = 1, priceCents = 102, qty = 50))
            book.addOrder(bid(id = 2, priceCents = 100, qty = 30))

            assertEquals(100L, book.getBestBid())
            assertNotNull(book.bids[100L], "Price level 100 must exist in bids tree")
            assertEquals(30L, book.bids[100L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("A non-crossing ASK parks in the asks tree")
        fun `non-crossing ask parks in asks tree`() {
            book.addOrder(bid(id = 1, priceCents = 99, qty = 20))
            book.addOrder(ask(id = 2, priceCents = 101, qty = 40))

            assertEquals(101L, book.getBestAsk())
            assertNotNull(book.asks[101L], "Price level 101 must exist in asks tree")
            assertEquals(40L, book.asks[101L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("Multiple orders at the same price accumulate volume in one level")
        fun `multiple orders at same price accumulate volume`() {
            book.addOrder(ask(id = 1, priceCents = 101, qty = 50))
            book.addOrder(ask(id = 2, priceCents = 101, qty = 30))

            assertEquals(80L, book.asks[101L]!!.totalVolume.value)
            assertEquals(1, book.asks.size)
        }

        @Test
        @DisplayName("Empty book has no best bid or ask")
        fun `empty book returns null for best bid and ask`() {
            assertNull(book.getBestBid())
            assertNull(book.getBestAsk())
        }
    }


    @Nested
    @DisplayName("Price-Time priority (FIFO)")
    inner class PriceTimePriority {

        @Test
        @DisplayName("Within the same price level, earlier orders fill first")
        fun `fifo order within same price level`() {
            // Two asks at the same price: #1 arrived first
            book.addOrder(ask(id = 1, priceCents = 100, qty = 10, ts = 1))
            book.addOrder(ask(id = 2, priceCents = 100, qty = 10, ts = 2))

            val trades = book.process(bid(id = 3, priceCents = 100, qty = 10))

            assertEquals(1, trades.size)
            assertEquals(1L, trades[0].sellOrderId, "Order #1 (the oldest) must fill first")
            assertEquals(10L, book.asks[100L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("Better-priced ask is always matched before a worse one")
        fun `best price wins over worse price across levels`() {
            // Level 100 is a better price than 101 for the buyer
            book.addOrder(ask(id = 1, priceCents = 101, qty = 50))
            book.addOrder(ask(id = 2, priceCents = 100, qty = 50))

            val trades = book.process(bid(id = 3, priceCents = 101, qty = 50))

            assertEquals(1, trades.size)
            assertEquals(100L, trades[0].executionPrice.value, "Must match at the best (lowest) ask price")
            assertEquals(2L, trades[0].sellOrderId, "Order at the better price fills first")
        }

        @Test
        @DisplayName("Aggressive order sweeps multiple levels in price-priority order")
        fun `aggressive order sweeps levels in price order`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 20))
            book.addOrder(ask(id = 2, priceCents = 101, qty = 20))
            book.addOrder(ask(id = 3, priceCents = 102, qty = 20))

            // Buy 50 at market: sweeps level 100 fully, 101 fully, then 10 from 102
            val trades = book.process(bid(id = 4, priceCents = 102, qty = 50))

            assertEquals(3, trades.size)
            assertEquals(100L, trades[0].executionPrice.value)
            assertEquals(101L, trades[1].executionPrice.value)
            assertEquals(102L, trades[2].executionPrice.value)
            assertEquals(10L, trades[2].executedQuantity.value, "Only 10 of the 20 at 102 consumed")
        }
    }
    @Nested
    @DisplayName("Full Fill")
    inner class FullFill {

        @Test
        @DisplayName("Exact quantity match produces one trade and empties the level")
        fun `exact qty match produces one trade and prunes level`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 50))

            val trades = book.process(bid(id = 2, priceCents = 100, qty = 50))

            assertEquals(1, trades.size)
            assertEquals(50L, trades[0].executedQuantity.value)
            assertEquals(100L, trades[0].executionPrice.value)
            assertNull(book.asks[100L], "Price level must be removed when volume reaches zero")
            assertTrue(book.asks.isEmpty())
        }

        @Test
        @DisplayName("Aggressive order consumes multiple passive orders completely")
        fun `aggressive order fully consumes multiple passives`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 30))
            book.addOrder(ask(id = 2, priceCents = 100, qty = 20))

            val trades = book.process(bid(id = 3, priceCents = 100, qty = 50))

            assertEquals(2, trades.size)
            assertEquals(1L, trades[0].sellOrderId)
            assertEquals(30L, trades[0].executedQuantity.value)
            assertEquals(2L, trades[1].sellOrderId)
            assertEquals(20L, trades[1].executedQuantity.value)
            assertNull(book.asks[100L], "Level must be pruned after full consumption")
        }

        @Test
        @DisplayName("Fully-filled passive order is removed from orderMap")
        fun `fully-filled passive removed from orderMap`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 40))

            book.process(bid(id = 2, priceCents = 100, qty = 40))

            assertFalse(book.cancelOrder(1L), "A fully-filled order must no longer be in the map")
        }

        @Test
        @DisplayName("Incoming order with no residual is not added as passive")
        fun `fully consumed incoming order not parked`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 50))

            book.process(bid(id = 2, priceCents = 100, qty = 50))

            assertNull(book.bids[100L], "A fully-matched incoming order must not be parked")
            assertTrue(book.bids.isEmpty())
        }
    }
    @Nested
    @DisplayName("Partial Fill")
    inner class PartialFill {

        @Test
        @DisplayName("Aggressive order partially fills a passive and residual parks correctly")
        fun `aggressive partial fill parks residual on correct side`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 50))

            val trades = book.process(bid(id = 2, priceCents = 100, qty = 30))

            assertEquals(1, trades.size)
            assertEquals(30L, trades[0].executedQuantity.value)

            assertNotNull(book.asks[100L], "Ask level must remain with 20 units pending")
            assertEquals(20L, book.asks[100L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("Aggressive order larger than book parks its residual as passive")
        fun `over-sized aggressive order parks residual as passive`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 30))

            // Buy 50: fills 30, remaining 20 parks as a passive BID
            val trades = book.process(bid(id = 2, priceCents = 100, qty = 50))

            assertEquals(1, trades.size)
            assertEquals(30L, trades[0].executedQuantity.value)

            assertEquals(100L, book.getBestBid())
            assertEquals(20L, book.bids[100L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("Partially-filled passive order retains its correct remaining quantity")
        fun `partially filled passive keeps correct remaining quantity`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 100))

            book.process(bid(id = 2, priceCents = 100, qty = 60))

            assertEquals(40L, book.asks[100L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("Partial fill of passive: orderMap updated with new remaining quantity")
        fun `partial fill updates orderMap for passive order`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 80))

            book.process(bid(id = 2, priceCents = 100, qty = 50))

            // The partially-filled order is still live and must be cancellable
            assertTrue(book.cancelOrder(1L), "Partially-filled order must still be in the map and cancellable")
        }
    }
    @Nested
    @DisplayName("Price-level pruning")
    inner class PriceLevelPruning {

        @Test
        @DisplayName("Price level node is removed from TreeMap when last order is fully consumed")
        fun `price level pruned when volume hits zero via matching`() {
            book.addOrder(ask(id = 1, priceCents = 101, qty = 20))
            book.addOrder(ask(id = 2, priceCents = 102, qty = 20))

            book.process(bid(id = 3, priceCents = 101, qty = 20))

            assertNull(book.asks[101L], "Level 101 must be pruned")
            assertNotNull(book.asks[102L], "Level 102 must still exist")
            assertEquals(101L, book.asks.size.toLong().also { assertEquals(1L, it) }.let { 102L })
        }

        @Test
        @DisplayName("Price level node is removed when last order is cancelled")
        fun `price level pruned when last order at level is cancelled`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 50))

            book.cancelOrder(1L)

            assertNull(book.asks[100L], "Level must be pruned after cancelling its only order")
            assertTrue(book.asks.isEmpty())
        }

        @Test
        @DisplayName("Cancelling one of two orders at a level does not prune the level")
        fun `cancelling one of two orders does not prune level`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 50))
            book.addOrder(ask(id = 2, priceCents = 100, qty = 30))

            book.cancelOrder(1L)

            assertNotNull(book.asks[100L], "Level must still exist after partial cancellation")
            assertEquals(30L, book.asks[100L]!!.totalVolume.value)
        }

        @Test
        @DisplayName("Best bid/ask is promoted after the top level is fully consumed")
        fun `best price promoted after top level consumed`() {
            book.addOrder(ask(id = 1, priceCents = 100, qty = 20))
            book.addOrder(ask(id = 2, priceCents = 101, qty = 20))

            book.process(bid(id = 3, priceCents = 101, qty = 20))

            assertEquals(101L, book.getBestAsk(), "Best ask must now be 101 after 100 is consumed")
        }
    }
    @Nested
    @DisplayName("Order cancellation")
    inner class Cancellation {

        @Test
        @DisplayName("Cancelling a passive order returns true and removes it from the tree")
        fun `cancelling passive order returns true and removes it from tree`() {
            book.addOrder(bid(id = 10, priceCents = 99, qty = 100))

            val result = book.cancelOrder(10L)

            assertTrue(result)
            assertNull(book.bids[99L], "Level must be pruned after cancel")
        }

        @Test
        @DisplayName("Cancelling a non-existent order returns false")
        fun `cancelling unknown order id returns false`() {
            val result = book.cancelOrder(999L)

            assertFalse(result)
        }

        @Test
        @DisplayName("Cancelling the same order twice returns false on the second attempt")
        fun `double cancel returns false`() {
            book.addOrder(bid(id = 1, priceCents = 99, qty = 50))

            assertTrue(book.cancelOrder(1L))
            assertFalse(book.cancelOrder(1L), "Second cancel must return false — order already removed")
        }

        @Test
        @DisplayName("Cancel removes order from orderMap — subsequent cancel returns false")
        fun `cancel removes from orderMap`() {
            book.addOrder(ask(id = 5, priceCents = 101, qty = 40))

            book.cancelOrder(5L)

            assertFalse(book.cancelOrder(5L))
        }

        @Test
        @DisplayName("Cancelling one order at a multi-order level adjusts volume correctly")
        fun `cancel adjusts level volume correctly`() {
            book.addOrder(bid(id = 1, priceCents = 99, qty = 60))
            book.addOrder(bid(id = 2, priceCents = 99, qty = 40))

            book.cancelOrder(1L)

            assertEquals(40L, book.bids[99L]!!.totalVolume.value)
        }
    }
    @Nested
    @DisplayName("Spread and best price")
    inner class Spread {

        @Test
        @DisplayName("Bids tree keeps highest bid as best bid")
        fun `bids tree keeps highest bid as best`() {
            book.addOrder(bid(id = 1, priceCents = 97, qty = 10))
            book.addOrder(bid(id = 2, priceCents = 99, qty = 10))
            book.addOrder(bid(id = 3, priceCents = 98, qty = 10))

            assertEquals(99L, book.getBestBid())
        }

        @Test
        @DisplayName("Asks tree keeps lowest ask as best ask")
        fun `asks tree keeps lowest ask as best`() {
            book.addOrder(ask(id = 1, priceCents = 103, qty = 10))
            book.addOrder(ask(id = 2, priceCents = 101, qty = 10))
            book.addOrder(ask(id = 3, priceCents = 102, qty = 10))

            assertEquals(101L, book.getBestAsk())
        }

        @Test
        @DisplayName("Non-crossing order does not alter the opposite side's best price")
        fun `passive order does not alter opposite side best price`() {
            book.addOrder(ask(id = 1, priceCents = 102, qty = 50))
            val askBefore = book.getBestAsk()

            book.addOrder(bid(id = 2, priceCents = 100, qty = 50)) // does not cross

            assertEquals(askBefore, book.getBestAsk())
        }

        @Test
        @DisplayName("A crossing order that leaves residual updates the spread correctly")
        fun `crossing order updates spread via residual parking`() {
            book.addOrder(ask(id = 1, priceCents = 101, qty = 30))

            // Buy 50 @ 101: fills 30, remaining 20 parks as BID
            book.process(bid(id = 2, priceCents = 101, qty = 50))

            assertNull(book.getBestAsk(), "Ask side must be empty")
            assertEquals(101L, book.getBestBid(), "Residual must be the new best bid")
        }
    }
}
