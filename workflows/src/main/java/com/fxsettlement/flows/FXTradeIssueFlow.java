package com.fxsettlement.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.fxsettlement.contracts.FXTradeContract;
import com.fxsettlement.states.FXTradeState;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;

import java.util.Arrays;

import static java.util.Collections.singletonList;

public class FXTradeIssueFlow {

    @InitiatingFlow
    @StartableByRPC
    public static class FXTradeIssueFlowInitiator extends FlowLogic<SignedTransaction> {
        private final Party responder;
        private final String buysell;
        private final int buyamount;
        private final int sellamount;
        private final String buycurrency;
        private final String sellcurrency;
        private final float exchangerate;
        private final String tradedate;
        private final String settledate;
        private final String matchstatus;
        private final String settlementstatus;

        //private final String paymentstatus;

        //private final float amount;
        //private final String currencyPair;

        public FXTradeIssueFlowInitiator(Party responder, int buyamount, int sellamount, String buycurrency, String sellcurrency, String tradedate, String settledate, float exchangerate, String buysell, String matchstatus, String settlementstatus) {
            this.responder = responder;
            this.buyamount = buyamount;
            this.sellamount = sellamount;
            this.buycurrency = buycurrency;
            this.sellcurrency = sellcurrency;
            this.tradedate = tradedate;
            this.settledate = settledate;
            this.exchangerate = exchangerate;
            this.buysell = buysell;
            this.matchstatus = matchstatus;
            this.settlementstatus = settlementstatus;
        }

        private final ProgressTracker progressTracker = new ProgressTracker();

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {

            /** Explicit selection of notary by CordaX500Name - argument can by coded in flows or parsed from config (Preferred)*/
            final Party notary = getServiceHub().getNetworkMapCache().getNotary(CordaX500Name.parse("O=Notary,L=London,C=GB"));
            // We get a reference to our own identity.
            Party issuer = getOurIdentity();

            // We create our new TokenState.
            FXTradeState FXTradeState = new FXTradeState(issuer, responder, buyamount, sellamount, buycurrency,sellcurrency, tradedate, settledate, exchangerate, buysell,matchstatus,settlementstatus);

            // We build our transaction.
            TransactionBuilder transactionBuilder = new TransactionBuilder(notary)
                                                .addOutputState(FXTradeState)
                                                .addCommand(new FXTradeContract.Commands.Issue(), Arrays.asList(issuer.getOwningKey(),responder.getOwningKey()));

            // We check our transaction is valid based on its contracts.
            transactionBuilder.verify(getServiceHub());

            FlowSession session = initiateFlow(responder);

            // We sign the transaction with our private key, making it immutable.
            SignedTransaction signedTransaction = getServiceHub().signInitialTransaction(transactionBuilder);

            // The counterparty signs the transaction
            SignedTransaction fullySignedTransaction = subFlow(new CollectSignaturesFlow(signedTransaction, singletonList(session)));

            // We get the transaction notarised and recorded automatically by the platform.
            return subFlow(new FinalityFlow(fullySignedTransaction, singletonList(session)));
        }
    }

    @InitiatedBy(FXTradeIssueFlowInitiator.class)
    public static class FXTradeIssueFlowResponder extends FlowLogic<Void>{
        //private variable
        private FlowSession counterpartySession;

        //Constructor
        public FXTradeIssueFlowResponder(FlowSession counterpartySession) {
            this.counterpartySession = counterpartySession;
        }

        @Suspendable
        @Override
        public Void call() throws FlowException {
            SignedTransaction signedTransaction = subFlow(new SignTransactionFlow(counterpartySession) {
                @Suspendable
                @Override
                protected void checkTransaction(SignedTransaction stx) throws FlowException {
                    /*
                     * SignTransactionFlow will automatically verify the transaction and its signatures before signing it.
                     * However, just because a transaction is contractually valid doesn’t mean we necessarily want to sign.
                     * What if we don’t want to deal with the counterparty in question, or the value is too high,
                     * or we’re not happy with the transaction’s structure? checkTransaction
                     * allows us to define these additional checks. If any of these conditions are not met,
                     * we will not sign the transaction - even if the transaction and its signatures are contractually valid.
                     * ----------
                     * For this hello-world cordapp, we will not implement any aditional checks.
                     * */
                }
            });
            //Stored the transaction into data base.
            subFlow(new ReceiveFinalityFlow(counterpartySession, signedTransaction.getId()));
            return null;
        }
    }
}