package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.ZERO;

import java.math.BigDecimal;
import java.util.List;

final class AccountMovement {
    private BigDecimal longPnl = ZERO, shortPnl = ZERO;
    private boolean longClosed, shortClosed;
    private int externalCount;

    static AccountMovement verify(List<FuturesApi.AccountTrade> trades, BigDecimal longs, BigDecimal shorts,
            FuturesApi.Position actual, long ownOrder, BigDecimal ownQuantity) {
        AccountMovement movement = new AccountMovement();
        BigDecimal seenOwn = ZERO;
        for (FuturesApi.AccountTrade trade : trades) {
            BigDecimal buyDelta = trade.buy ? trade.quantity : trade.quantity.negate();
            boolean own = trade.orderId == ownOrder;
            if (own) seenOwn = seenOwn.add(trade.quantity);
            else movement.externalCount++;

            switch (trade.positionSide) {
                case "LONG":
                    if (!own && !trade.buy) movement.closed(true, trade.realizedPnl);
                    longs = longs.add(buyDelta);
                    break;
                case "SHORT":
                    if (!own && trade.buy) movement.closed(false, trade.realizedPnl);
                    shorts = shorts.subtract(buyDelta);
                    break;
                case "BOTH":
                    if (longs.signum() > 0 && shorts.signum() > 0) return null;
                    if (!own && longs.signum() > 0 && !trade.buy) movement.closed(true, trade.realizedPnl);
                    if (!own && shorts.signum() > 0 && trade.buy) movement.closed(false, trade.realizedPnl);
                    BigDecimal net = longs.subtract(shorts).add(buyDelta);
                    longs = net.max(ZERO);
                    shorts = net.negate().max(ZERO);
                    break;
                default:
                    return null;
            }
            if (longs.signum() < 0 || shorts.signum() < 0) return null;
        }
        if (movement.externalCount == 0 || seenOwn.compareTo(ownQuantity) != 0
                || longs.compareTo(actual.longs) != 0 || shorts.compareTo(actual.shorts) != 0) return null;
        return movement;
    }

    boolean closed(boolean isLong) { return isLong ? longClosed : shortClosed; }

    BigDecimal pnl(boolean isLong) { return isLong ? longPnl : shortPnl; }

    private void closed(boolean isLong, BigDecimal pnl) {
        if (isLong) {
            longClosed = true;
            longPnl = longPnl == null || pnl == null ? null : longPnl.add(pnl);
        } else {
            shortClosed = true;
            shortPnl = shortPnl == null || pnl == null ? null : shortPnl.add(pnl);
        }
    }
}
