package main.java.com.rubenjpdev.ordermatching.engine

import main.java.com.rubenjpdev.ordermatching.model.Order
import main.java.com.rubenjpdev.ordermatching.model.Price
import main.java.com.rubenjpdev.ordermatching.model.Quantity
import main.java.com.rubenjpdev.ordermatching.model.Side
import main.java.com.rubenjpdev.ordermatching.model.Trade
import java.util.TreeMap
import kotlin.math.min

class OrderBook(val asset: String) {
    internal val asks = TreeMap<Long, PriceLevel>();
    internal val bids = TreeMap<Long, PriceLevel>(compareByDescending { it });
    private val orderMap = HashMap<Long, Order>();
    private var tradeCounter = 0L;

    fun getBestBid(): Long? = bids.firstEntry()?.key;
    fun getBestAsk(): Long? = asks.firstEntry()?.key;

    fun addOrder(order: Order){
        val targetTree = when(order.side){
            Side.BID -> bids
            Side.ASK -> asks
        }
        val priceKey = order.priceCents.value;
        val level = targetTree.getOrPut(priceKey){PriceLevel()}

        level.addOrder(order);
        orderMap[order.id] = order;
    }

    fun cancelOrder(orderId: Long): Boolean{
        val order = orderMap.remove(orderId) ?: return false;

        val targetTree = when(order.side){
            Side.BID -> bids
            Side.ASK -> asks
        }

        val priceKey = order.priceCents.value;

        val level = targetTree[priceKey]?: return false;

        val removed = level.removeOrder(orderId);

        if(level.isEmpty) targetTree.remove(priceKey);
        return removed;
    }

    fun process(incomingOrder: Order): List<Trade>{
        val trades = mutableListOf<Trade>();
        var remainingQty = incomingOrder.quantity.value;

        val oposingTree = when(incomingOrder.side){
            Side.BID -> asks
            Side.ASK -> bids
        }

        while(remainingQty > 0L && oposingTree.isNotEmpty()){
            val bestEntry = oposingTree.firstEntry();
            val bestPrice = bestEntry.key;
            val level = bestEntry.value;

            val crosses = when(incomingOrder.side){
                Side.BID -> incomingOrder.priceCents.value >= bestPrice
                Side.ASK -> incomingOrder.priceCents.value <= bestPrice
            }

            if(!crosses) break

            while(remainingQty > 0L && !level.isEmpty){
                val passiveOrder = level.peekNextOrder()!!

                val fillQty = min(remainingQty, passiveOrder.quantity.value)

                val trade = Trade(
                    tradeId = ++tradeCounter,
                    buyOrderId = if(incomingOrder.side == Side.BID) incomingOrder.id else passiveOrder.id,
                    sellOrderId =  if(incomingOrder .side == Side.ASK) incomingOrder.id else passiveOrder.id,
                    executionPrice = Price(bestPrice),
                    executedQuantity = Quantity(fillQty)
                )
                trades.add(trade)
                remainingQty -= fillQty;
                val passiveRemaining = passiveOrder.quantity.value - fillQty;

                if(passiveRemaining > 0L) {
                    val updatedPassive = passiveOrder.copy(quantity = Quantity(passiveRemaining));
                    level.popNextOrder();
                    level.addFirst(updatedPassive);
                    orderMap[passiveOrder.id] = updatedPassive;
                    break
                }else {
                    level.popNextOrder();
                    orderMap.remove(passiveOrder.id);
                }
            }

            if(level.isEmpty) oposingTree.remove(bestPrice);
        }

        if(remainingQty > 0L){
            val residualOrder = incomingOrder.copy(quantity = Quantity(remainingQty));
            addOrder(residualOrder);
        }
        return trades;
    }
}