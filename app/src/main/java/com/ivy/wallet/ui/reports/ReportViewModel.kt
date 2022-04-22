package com.ivy.wallet.ui.reports

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ivy.design.navigation.Navigation
import com.ivy.design.viewmodel.IvyViewModel
import com.ivy.wallet.domain.data.TransactionType
import com.ivy.wallet.domain.data.entity.Account
import com.ivy.wallet.domain.data.entity.Category
import com.ivy.wallet.domain.data.entity.Transaction
import com.ivy.wallet.domain.logic.PlannedPaymentsLogic
import com.ivy.wallet.domain.logic.WalletLogic
import com.ivy.wallet.domain.logic.csv.ExportCSVLogic
import com.ivy.wallet.domain.logic.currency.ExchangeRatesLogic
import com.ivy.wallet.domain.pure.wallet.withDateDividers
import com.ivy.wallet.io.persistence.dao.*
import com.ivy.wallet.ui.IvyWalletCtx
import com.ivy.wallet.ui.RootActivity
import com.ivy.wallet.ui.onboarding.model.TimePeriod
import com.ivy.wallet.ui.paywall.PaywallReason
import com.ivy.wallet.ui.theme.Gray
import com.ivy.wallet.utils.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val plannedPaymentsLogic: PlannedPaymentsLogic,
    private val settingsDao: SettingsDao,
    private val walletLogic: WalletLogic,
    private val transactionDao: TransactionDao,
    private val ivyContext: IvyWalletCtx,
    private val nav: Navigation,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val exchangeRatesLogic: ExchangeRatesLogic,
    private val exchangeRateDao: ExchangeRateDao,
    private val exportCSVLogic: ExportCSVLogic
) : IvyViewModel<ReportScreenState>() {
    override val mutableState: MutableStateFlow<ReportScreenState> = MutableStateFlow(
        ReportScreenState()
    )
    private val unSpecifiedCategory = Category("UnSpecified", color = Gray.toArgb())

    private val _period = MutableLiveData<TimePeriod>()
    val period = _period.asLiveData()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories = _categories.readOnly()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts = _accounts.readOnly()

    private val _baseCurrency = MutableStateFlow("")
    val baseCurrency = _baseCurrency.readOnly()

    private val _filter = MutableStateFlow<ReportFilter?>(null)
    val filter = _filter.readOnly()

    fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            _baseCurrency.value = settingsDao.findFirst().currency
            _accounts.value = accountDao.findAll()
            _categories.value = listOf(unSpecifiedCategory) + categoryDao.findAll()

            updateState {
                it.copy(
                    baseCurrency = _baseCurrency.value,
                    categories = _categories.value,
                    accounts = _accounts.value
                )
            }
        }
    }

    private suspend fun setFilter(filter: ReportFilter?) {
        scopedIOThread { scope ->
            if (filter == null) {
                //clear filter
                _filter.value = null
                return@scopedIOThread
            }

            if (!filter.validate()) return@scopedIOThread
            val accounts = accounts.value
            val baseCurrency = baseCurrency.value
            _filter.value = filter

            updateState {
                it.copy(loading = true, filter = _filter.value)
            }

            val transactions = filterTransactions(
                baseCurrency = baseCurrency,
                accounts = accounts,
                filter = filter
            )

            val history = transactions
                .filter { it.dateTime != null }
                .sortedByDescending { it.dateTime }

            val historyWithDateDividers = scope.async {
                history.withDateDividers(
                    exchangeRateDao = exchangeRateDao,
                    accountDao = accountDao,
                    baseCurrencyCode = _baseCurrency.value
                )
            }

            val income = scope.async { walletLogic.calculateIncome(history) }
            val expenses = scope.async { walletLogic.calculateExpenses(history) }

            val balance = scope.async {
                calculateBalance(
                    baseCurrency = baseCurrency,
                    accounts = accounts,
                    history = history,
                    income = income.await(),
                    expenses = expenses.await(),
                    filter = filter
                )
            }

            val accountFilterIdList = scope.async { filter.accounts.map { it.id } }

            val timeNowUTC = timeNowUTC()

            //Upcoming
            val upcomingTransactions = transactions
                .filter {
                    it.dueDate != null && it.dueDate.isAfter(timeNowUTC)
                }
                .sortedBy { it.dueDate }
            val upcomingIncome = scope.async { walletLogic.calculateIncome(upcomingTransactions) }
            val upcomingExpenses =
                scope.async { walletLogic.calculateExpenses(upcomingTransactions) }

            //Overdue
            val overdue = transactions.filter {
                it.dueDate != null && it.dueDate.isBefore(timeNowUTC)
            }.sortedByDescending {
                it.dueDate
            }
            val overdueIncome = scope.async { walletLogic.calculateIncome(overdue) }
            val overdueExpenses = scope.async { walletLogic.calculateExpenses(overdue) }

            updateState {
                it.copy(
                    income = income.await(),
                    expenses = expenses.await(),
                    upcomingIncome = upcomingIncome.await(),
                    upcomingExpenses = upcomingExpenses.await(),
                    overdueIncome = overdueIncome.await(),
                    overdueExpenses = overdueExpenses.await(),
                    history = historyWithDateDividers.await(),
                    upcomingTransactions = upcomingTransactions,
                    overdueTransactions = overdue,
                    categories = categories.value,
                    accounts = _accounts.value,
                    filter = filter,
                    loading = false,
                    accountIdFilters = accountFilterIdList.await(),
                    transactions = transactions,
                    balance = balance.await(),
                    filterOverlayVisible = false
                )
            }
        }
    }

    private fun filterTransactions(
        baseCurrency: String,
        accounts: List<Account>,
        filter: ReportFilter,
    ): List<Transaction> {
        val filterAccountIds = filter.accounts.map { it.id }
        val filterCategoryIds =
            filter.categories.map { if (it.id == unSpecifiedCategory.id) null else it.id }
        val filterRange = filter.period?.toRange(ivyContext.startDayOfMonth)

        return transactionDao
            .findAll()
            .asSequence()
            .filter {
                //Filter by Transaction Type
                filter.trnTypes.contains(it.type)
            }
            .filter {
                //Filter by Time Period

                filterRange ?: return@filter false

                (it.dateTime != null && filterRange.includes(it.dateTime)) ||
                        (it.dueDate != null && filterRange.includes(it.dueDate))
            }
            .filter { trn ->
                //Filter by Accounts

                filterAccountIds.contains(trn.accountId) || //Transfers Out
                        (trn.toAccountId != null && filterAccountIds.contains(trn.toAccountId)) //Transfers In
            }
            .filter { trn ->
                //Filter by Categories

                filterCategoryIds.contains(trn.smartCategoryId()) || (trn.type == TransactionType.TRANSFER)
            }
            .filter {
                //Filter by Amount
                //!NOTE: Amount must be converted to baseCurrency amount

                val trnAmountBaseCurrency = exchangeRatesLogic.amountBaseCurrency(
                    transaction = it,
                    baseCurrency = baseCurrency,
                    accounts = accounts
                )

                (filter.minAmount == null || trnAmountBaseCurrency >= filter.minAmount) &&
                        (filter.maxAmount == null || trnAmountBaseCurrency <= filter.maxAmount)
            }
            .filter {
                //Filter by Included Keywords

                val includeKeywords = filter.includeKeywords
                if (includeKeywords.isEmpty()) return@filter true

                if (it.title != null && it.title.isNotEmpty()) {
                    includeKeywords.forEach { keyword ->
                        if (it.title.containsLowercase(keyword)) {
                            return@filter true
                        }
                    }
                }

                if (it.description != null && it.description.isNotEmpty()) {
                    includeKeywords.forEach { keyword ->
                        if (it.description.containsLowercase(keyword)) {
                            return@filter true
                        }
                    }
                }

                false
            }
            .filter {
                //Filter by Excluded Keywords

                val excludedKeywords = filter.excludeKeywords
                if (excludedKeywords.isEmpty()) return@filter true

                if (it.title != null && it.title.isNotEmpty()) {
                    excludedKeywords.forEach { keyword ->
                        if (it.title.containsLowercase(keyword)) {
                            return@filter false
                        }
                    }
                }

                if (it.description != null && it.description.isNotEmpty()) {
                    excludedKeywords.forEach { keyword ->
                        if (it.description.containsLowercase(keyword)) {
                            return@filter false
                        }
                    }
                }

                true
            }
            .toList()
    }

    private fun String.containsLowercase(anotherString: String): Boolean {
        return this.toLowerCaseLocal().contains(anotherString.toLowerCaseLocal())
    }

    private fun calculateBalance(
        baseCurrency: String,
        accounts: List<Account>,
        history: List<Transaction>,
        income: Double,
        expenses: Double,
        filter: ReportFilter
    ): Double {
        val includedAccountsIds = filter.accounts.map { it.id }
        //+ Transfers In (#conv to BaseCurrency)
        val transfersIn = history
            .filter {
                it.type == TransactionType.TRANSFER &&
                        it.toAccountId != null && includedAccountsIds.contains(it.toAccountId)
            }
            .sumOf { trn ->
                exchangeRatesLogic.toAmountBaseCurrency(
                    transaction = trn,
                    baseCurrency = baseCurrency,
                    accounts = accounts
                )
            }

        //- Transfers Out (#conv to BaseCurrency)
        val transfersOut = history
            .filter {
                it.type == TransactionType.TRANSFER &&
                        includedAccountsIds.contains(it.accountId)
            }
            .sumOf { trn ->
                exchangeRatesLogic.amountBaseCurrency(
                    transaction = trn,
                    baseCurrency = baseCurrency,
                    accounts = accounts
                )
            }

        //Income - Expenses (#conv to BaseCurrency)
        return income - expenses + transfersIn - transfersOut
    }

    private fun export(context: Context) {
        ivyContext.protectWithPaywall(
            paywallReason = PaywallReason.EXPORT_CSV,
            navigation = nav
        ) {
            val filter = _filter.value ?: return@protectWithPaywall
            if (!filter.validate()) return@protectWithPaywall
            val accounts = _accounts.value
            val baseCurrency = _baseCurrency.value

            ivyContext.createNewFile(
                "Report (${
                    timeNowUTC().formatNicelyWithTime(noWeekDay = true)
                }).csv"
            ) { fileUri ->
                viewModelScope.launch {
                    updateState {
                        it.copy(loading = true)
                    }

                    exportCSVLogic.exportToFile(
                        context = context,
                        fileUri = fileUri,
                        exportScope = {
                            filterTransactions(
                                baseCurrency = baseCurrency,
                                accounts = accounts,
                                filter = filter
                            )
                        }
                    )

                    (context as RootActivity).shareCSVFile(
                        fileUri = fileUri
                    )

                    updateState {
                        it.copy(loading = false)
                    }
                }
            }
        }
    }

    private fun setUpcomingExpanded(expanded: Boolean) {
        updateStateNonBlocking {
            it.copy(upcomingExpanded = expanded)
        }
    }

    private fun setOverdueExpanded(expanded: Boolean) {
        updateStateNonBlocking {
            it.copy(overdueExpanded = expanded)
        }
    }

    private suspend fun payOrGet(transaction: Transaction) {
        uiThread {
            plannedPaymentsLogic.payOrGet(transaction = transaction) {
                start()
            }
        }
    }

    private fun setFilterOverlayVisible(filterOverlayVisible: Boolean) {
        updateStateNonBlocking {
            it.copy(filterOverlayVisible = filterOverlayVisible)
        }
    }

    fun onEvent(event: ReportScreenEvent) {
        viewModelScope.launch(Dispatchers.Default) {
            when (event) {
                is ReportScreenEvent.OnFilter -> setFilter(event.filter)
                is ReportScreenEvent.OnExport -> export(event.context)
                is ReportScreenEvent.OnPayOrGet -> payOrGet(event.transaction)
                is ReportScreenEvent.OnOverdueExpanded -> setOverdueExpanded(event.overdueExpanded)
                is ReportScreenEvent.OnUpcomingExpanded -> setUpcomingExpanded(event.upcomingExpanded)
                is ReportScreenEvent.OnFilterOverlayVisible -> setFilterOverlayVisible(event.filterOverlayVisible)
            }
        }
    }
}