"""Orders demo application.

Publishes:
  orders       - keyed input table (key = OrderId); the simulator upserts rows here
  entitlements - keyed input table mapping realm role -> entitled Region; editable live
  orders_us    - rows entitled to the trader-us role
  orders_emea  - rows entitled to the trader-emea role
  orders_all   - rows entitled to the dh-admin role (all regions)

Row-level security model: a role sees exactly the regions listed for it in `entitlements`.
The views use where_in against the ticking entitlements table, so adding/removing an
entitlement row updates every affected view immediately - no restart needed.

NOTE: open-source Deephaven has no built-in entitlement system (that is a Deephaven
Enterprise feature). This is script-level filtering: it relies on clients only being handed
the per-role views, and on the console being disabled in hardened deployments so users
cannot reach the unfiltered `orders` table. See the repo README for the full caveats.
"""

from deephaven import dtypes as dht
from deephaven import input_table, new_table
from deephaven.appmode import get_app_state
from deephaven.column import double_col, int_col, long_col, string_col

ORDER_COLUMNS = {
    "OrderId": dht.long,
    "Symbol": dht.string,
    "Region": dht.string,
    "Desk": dht.string,
    "Trader": dht.string,
    "Side": dht.string,
    "Qty": dht.int32,
    "Price": dht.double,
    "Status": dht.string,
    "LastUpdated": dht.Instant,
}

orders = input_table(col_defs=ORDER_COLUMNS, key_cols=["OrderId"])

_seed = (
    new_table(
        [
            long_col("OrderId", [1001, 1002, 1003, 1004, 1005, 1006]),
            string_col("Symbol", ["AAPL", "MSFT", "SAP.DE", "AZN.L", "7203.T", "9988.HK"]),
            string_col("Region", ["US", "US", "EMEA", "EMEA", "APAC", "APAC"]),
            string_col("Desk", ["US-EQ", "US-EQ", "EMEA-EQ", "EMEA-EQ", "APAC-EQ", "APAC-EQ"]),
            string_col("Trader", ["alice", "alice", "bob", "bob", "carol", "carol"]),
            string_col("Side", ["BUY", "SELL", "BUY", "BUY", "SELL", "BUY"]),
            int_col("Qty", [1000, 500, 2000, 700, 1500, 300]),
            double_col("Price", [189.5, 415.2, 178.9, 121.4, 2450.0, 84.3]),
            string_col("Status", ["NEW", "WORKING", "NEW", "FILLED", "NEW", "WORKING"]),
        ]
    )
    .update("LastUpdated = now()")
    .select(list(ORDER_COLUMNS))
)
orders.add(_seed)

entitlements = input_table(
    init_table=new_table(
        [
            string_col("Role", ["trader-us", "trader-emea", "dh-admin", "dh-admin", "dh-admin"]),
            string_col("Region", ["US", "EMEA", "US", "EMEA", "APAC"]),
        ]
    ),
    key_cols=["Role", "Region"],
)


def _entitled_view(role: str):
    return orders.where_in(entitlements.where(f"Role == `{role}`"), ["Region"])


orders_us = _entitled_view("trader-us")
orders_emea = _entitled_view("trader-emea")
orders_all = _entitled_view("dh-admin")

# Publish as application fields (ticket namespace a/orders-app/f/<name>); script globals are
# not exported automatically.
_app = get_app_state()
_app["orders"] = orders
_app["entitlements"] = entitlements
_app["orders_us"] = orders_us
_app["orders_emea"] = orders_emea
_app["orders_all"] = orders_all
