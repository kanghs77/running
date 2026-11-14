package app.runmate

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import app.runmate.core.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "개발자에게 커피 선물하기": Google Play 인앱결제(소모성 상품 1개).
 * Play 스토어에서 설치했고 Play Console에 상품이 등록되어 있을 때만 가격이 조회되어 버튼이 보인다.
 * 결제·환불·영수증은 모두 Google Play가 처리하며, 앱은 결제 정보를 보관하지 않는다.
 */
interface CoffeeStore {
    /** Play가 알려준 현지 통화 표시 가격(예: "₩2,900"). null이면 구매할 수 없어 버튼을 숨긴다. */
    val price: StateFlow<String?>
    /** 화면이 보일 때 호출: 연결하고 가격을 조회한다. */
    fun refresh()
    fun buy(activity: Activity)
}

class PlayCoffee(context: Context) : CoffeeStore, PurchasesUpdatedListener {
    private val _price = MutableStateFlow<String?>(null)
    override val price: StateFlow<String?> get() = _price
    private var details: ProductDetails? = null
    @Volatile private var connecting = false

    private val client: BillingClient? = try {
        BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    } catch (e: Throwable) { null }

    override fun refresh() {
        val c = client ?: return
        try {
            if (c.isReady) { query(c); return }
            if (connecting) return
            connecting = true
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    connecting = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) query(c) else _price.value = null
                }
                override fun onBillingServiceDisconnected() { connecting = false }
            })
        } catch (e: Throwable) { connecting = false; _price.value = null }
    }

    private fun query(c: BillingClient) {
        try {
            val params = QueryProductDetailsParams.newBuilder().setProductList(
                listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build())
            ).build()
            c.queryProductDetailsAsync(params) { result, found ->
                val d = if (result.responseCode == BillingClient.BillingResponseCode.OK) found.productDetailsList.firstOrNull() else null
                details = d
                _price.value = d?.oneTimePurchaseOfferDetails?.formattedPrice
            }
            // 이전에 결제됐지만 처리(소모)되지 못한 건이 있으면 마무리한다
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, list ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) list.forEach { finish(it, thank = false) }
            }
        } catch (e: Throwable) { _price.value = null }
    }

    override fun buy(activity: Activity) {
        val c = client; val d = details
        if (c == null || d == null || !c.isReady) { RunController.notice.value = tr("지금은 결제를 진행할 수 없어요.", "Purchases aren't available right now."); refresh(); return }
        try {
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())
            ).build()
            c.launchBillingFlow(activity, params)
        } catch (e: Throwable) { RunController.notice.value = tr("지금은 결제를 진행할 수 없어요.", "Purchases aren't available right now.") }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { finish(it, thank = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            else -> RunController.notice.value = tr("결제가 완료되지 않았어요.", "The purchase wasn't completed.")
        }
    }

    /** 결제 완료 건을 소모 처리해 다음에도 다시 선물할 수 있게 한다. 대기 중(미결제) 건은 건드리지 않는다. */
    private fun finish(p: Purchase, thank: Boolean) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        try {
            client?.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r, _ ->
                if (thank && r.responseCode == BillingClient.BillingResponseCode.OK)
                    RunController.notice.value = tr("커피 잘 마실게요. 고맙습니다! ☕", "Thank you for the coffee! ☕")
            }
        } catch (e: Throwable) { }
    }

    companion object {
        /** Play Console의 인앱 상품 ID와 같아야 한다 */
        const val PRODUCT_ID = "coffee"
    }
}
