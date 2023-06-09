package com.fxsettlement.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.corda.client.jackson.JacksonSupport;
import net.corda.core.contracts.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.node.NodeInfo;
import net.corda.core.transactions.SignedTransaction;

/*
import net.corda.finance.contracts.asset.Cash;
import net.corda.samples.obligation.flows.IOUIssueFlow;
import net.corda.samples.obligation.flows.IOUSettleFlow;
import net.corda.samples.obligation.flows.IOUTransferFlow;
import net.corda.samples.obligation.flows.SelfIssueCashFlow;
import net.corda.samples.obligation.states.IOUState;
*/

import com.fxsettlement.states.FXTradeState;
import com.fxsettlement.flows.FXTradeIssueFlow;
import com.fxsettlement.flows.FXTradeSettleFlow;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.lang.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

import java.util.stream.Collectors;
import java.util.stream.Stream;

//import static net.corda.finance.workflows.GetBalances.getCashBalances;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

/**
 * Define your API endpoints here.
 */
@RestController
@RequestMapping("/api/fx") // The paths for HTTP requests are relative to this base path.
public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(RestController.class);
    private final CordaRPCOps proxy;
    private final CordaX500Name me;

    public MainController(NodeRPCConnection rpc) {
        this.proxy = rpc.getProxy();
        this.me = proxy.nodeInfo().getLegalIdentities().get(0).getName();

    }

    /** Helpers for filtering the network map cache. */
    public String toDisplayString(X500Name name){
        return BCStyle.INSTANCE.toString(name);
    }

    private boolean isNotary(NodeInfo nodeInfo) {
        return !proxy.notaryIdentities()
                .stream().filter(el -> nodeInfo.isLegalIdentity(el))
                .collect(Collectors.toList()).isEmpty();
    }

    private boolean isMe(NodeInfo nodeInfo){
        return nodeInfo.getLegalIdentities().get(0).getName().equals(me);
    }

    private boolean isNetworkMap(NodeInfo nodeInfo){
        return nodeInfo.getLegalIdentities().get(0).getName().getOrganisation().equals("Network Map Service");
    }

    @Configuration
    class Plugin {
        @Bean
        public ObjectMapper registerModule() {
            return JacksonSupport.createNonRpcMapper();
        }
    }

    @GetMapping(value = "/status", produces = TEXT_PLAIN_VALUE)
    private String status() {
        return "200";
    }

    @GetMapping(value = "/servertime", produces = TEXT_PLAIN_VALUE)
    private String serverTime() {
        return (LocalDateTime.ofInstant(proxy.currentNodeTime(), ZoneId.of("UTC"))).toString();
    }

    @GetMapping(value = "/addresses", produces = TEXT_PLAIN_VALUE)
    private String addresses() {
        return proxy.nodeInfo().getAddresses().toString();
    }

    @GetMapping(value = "/identities", produces = TEXT_PLAIN_VALUE)
    private String identities() {
        return proxy.nodeInfo().getLegalIdentities().toString();
    }

    @GetMapping(value = "/platformversion", produces = TEXT_PLAIN_VALUE)
    private String platformVersion() {
        return Integer.toString(proxy.nodeInfo().getPlatformVersion());
    }

    @GetMapping(value = "/peers", produces = APPLICATION_JSON_VALUE)
    public HashMap<String, List<String>> getPeers() {
        HashMap<String, List<String>> myMap = new HashMap<>();

        // Find all nodes that are not notaries, ourself, or the network map.
        Stream<NodeInfo> filteredNodes = proxy.networkMapSnapshot().stream()
                .filter(el -> !isNotary(el) && !isMe(el) && !isNetworkMap(el));
        // Get their names as strings
        List<String> nodeNames = filteredNodes.map(el -> el.getLegalIdentities().get(0).getName().toString())
                .collect(Collectors.toList());

        myMap.put("peers", nodeNames);
        return myMap;
    }

    @GetMapping(value = "/notaries", produces = TEXT_PLAIN_VALUE)
    private String notaries() {
        return proxy.notaryIdentities().toString();
    }

    @GetMapping(value = "/flows", produces = TEXT_PLAIN_VALUE)
    private String flows() {
        return proxy.registeredFlows().toString();
    }

    @GetMapping(value = "/states", produces = TEXT_PLAIN_VALUE)
    private String states() {
        return proxy.vaultQuery(ContractState.class).getStates().toString();
    }

    @GetMapping(value = "/me",produces = APPLICATION_JSON_VALUE)
    private HashMap<String, String> whoami(){
        HashMap<String, String> myMap = new HashMap<>();
        myMap.put("me", me.toString());
        return myMap;
    }

    @GetMapping(value = "/fxtrades",produces = APPLICATION_JSON_VALUE)
    public List<StateAndRef<FXTradeState>> getFXTrades() {
        // Filter by states type: FXTrades.
        return proxy.vaultQuery(FXTradeState.class).getStates();
    }

    @PutMapping(value =  "/submitTrade" , produces = TEXT_PLAIN_VALUE )
    public ResponseEntity<String> issueFXTrade( @RequestParam(value = "responder") String responder,
                                                @RequestParam(value = "buyamount") Integer buyamount,
                                                @RequestParam(value = "sellamount") Integer sellamount,
                                                @RequestParam(value = "sellcurrency") String sellcurrency,
                                                @RequestParam(value = "buycurrency") String buycurrency,
                                                @RequestParam(value = "tradedate") String tradedate,
                                                @RequestParam(value = "settledate") String settledate,
                                                @RequestParam(value = "exchangerate") Float exchangerate,
                                                @RequestParam(value = "buysell") String buysell,
                                                @RequestParam(value = "matchstatus") String matchstatus,
                                                @RequestParam(value = "settlementstatus") String settlementstatus
    ) throws IllegalArgumentException {
        // Get party objects for myself and the counterparty.
        Party me = proxy.nodeInfo().getLegalIdentities().get(0);
        Party lender;
        if(responder=="PartyA"){
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyA,L=London,C=GB"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }else if (responder=="PartyB"){
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyB,L=New York,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }else if (responder =="PartyC"){
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyC,L=Los Angelos,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }else {
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyC,L=Los Angelos,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }
        //Party lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(responder))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        // Create a new IOU states using the parameters given.
        try {
            FXTradeState state = new FXTradeState(me, lender, buyamount.intValue(), sellamount.intValue(), buycurrency,sellcurrency,tradedate,"032123",80,"Buy","UNMATCHED","SETTLED");
            // Start the IOUIssueFlow. We block and waits for the flows to return.
            SignedTransaction result = proxy.startTrackedFlowDynamic(FXTradeIssueFlow.FXTradeIssueFlowInitiator.class,lender, buyamount.intValue(), sellamount.intValue(), buycurrency, sellcurrency, tradedate, settledate ,exchangerate.floatValue(), buysell, matchstatus, settlementstatus ).getReturnValue().get();
            //SignedTransaction result = proxy.startTrackedFlowDynamic(FXTradeIssueFlow.FXTradeIssueFlowInitiator.class,state).getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction id "+ result.getId() + " && UniqueIdentifier "+ result.getTx() + " committed to ledger.\n " + result.getTx().getOutput(0));
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

    @PutMapping(value =  "/submitSettlement" , produces = TEXT_PLAIN_VALUE )
    public ResponseEntity<String> issueFXSettlement( @RequestParam(value = "responder") String responder,
                                                @RequestParam(value = "buyamount") Integer buyamount,
                                                @RequestParam(value = "sellamount") Integer sellamount,
                                                @RequestParam(value = "sellcurrency") String sellcurrency,
                                                @RequestParam(value = "buycurrency") String buycurrency,
                                                @RequestParam(value = "tradedate") String tradedate,
                                                @RequestParam(value = "settledate") String settledate,
                                                @RequestParam(value = "exchangerate") Float exchangerate,
                                                @RequestParam(value = "buysell") String buysell,
                                                @RequestParam(value = "matchstatus") String matchstatus,
                                                @RequestParam(value = "settlementstatus") String settlementstatus
    ) throws IllegalArgumentException {
        // Get party objects for myself and the counterparty.
        Party me = proxy.nodeInfo().getLegalIdentities().get(0);
        Party lender;
        if(responder=="PartyA"){
             lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyA,L=London,C=GB"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }else if (responder=="PartyB"){
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyB,L=New York,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }else if (responder =="PartyC"){
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyC,L=Los Angelos,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }else {
            lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyC,L=Los Angelos,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        }
        //Party lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(responder))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        // Create a new IOU states using the parameters given.
        try {
            FXTradeState state = new FXTradeState(me, lender, buyamount.intValue(), sellamount.intValue(), buycurrency,sellcurrency,tradedate,"032123",80,"Buy","UNMATCHED","SETTLED");
            // Start the IOUIssueFlow. We block and waits for the flows to return.
            SignedTransaction result = proxy.startTrackedFlowDynamic(FXTradeSettleFlow.FXTradeSettleFlowInitiator.class,lender, buyamount.intValue(), sellamount.intValue(), buycurrency, sellcurrency, tradedate, settledate ,exchangerate.floatValue(), buysell, matchstatus, settlementstatus ).getReturnValue().get();
            //SignedTransaction result = proxy.startTrackedFlowDynamic(FXTradeIssueFlow.FXTradeIssueFlowInitiator.class,state).getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction id "+ result.getId() + " && UniqueIdentifier "+ result.getTx()+  "committed to ledger.\n " + result.getTx().getOutput(0));
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

    //@RequestMapping(value="/submitTrade", method = RequestMethod.POST)
    @PutMapping(value =  "/submitTrade-Test", produces = TEXT_PLAIN_VALUE )
    public ResponseEntity<String> issueFXTrade(@RequestParam(value = "buyamount") Integer buyamount,
                                               @RequestParam(value = "sellamount") Integer sellamount,
                                               @RequestParam(value = "sellcurrency") String sellcurrency,
                                               @RequestParam(value = "buycurrency") String buycurrency,
                                               @RequestParam(value = "tradedate") String tradedate
                                               ) throws IllegalArgumentException {
        // Get party objects for myself and the counterparty.
        Party me = proxy.nodeInfo().getLegalIdentities().get(0);
        Party lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyC,L=Los Angelos,C=US"))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        // Create a new IOU states using the parameters given.
        try {
            FXTradeState state = new FXTradeState(me, lender, buyamount.intValue(), sellamount.intValue(), buycurrency,sellcurrency,tradedate,"032123",80,"Buy","UNMATCHED","SETTLED");
            // Start the IOUIssueFlow. We block and waits for the flows to return.
            float er = 80.0f;
            SignedTransaction result = proxy.startTrackedFlowDynamic(FXTradeIssueFlow.FXTradeIssueFlowInitiator.class,lender, buyamount.intValue(), sellamount.intValue(), buycurrency, sellcurrency, tradedate, "032123", er, "Buy", "UNMATCHED", "SETTLED" ).getReturnValue().get();
            //SignedTransaction result = proxy.startTrackedFlowDynamic(FXTradeIssueFlow.FXTradeIssueFlowInitiator.class,state).getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction id "+ result.getId() +" committed to ledger.\n " + result.getTx().getOutput(0));
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

    /*
    Party getCordaPartyMapping(String party){
     /*
        Party cordaParty;
        if (party == "PartyA")
            cordaParty = new Party(O=PartyA,L=London,C=GB);
        else if (party == "PartyB") {
            cordaParty = "O=PartyB,L=New York,C=US";
        }
        else if (party == "PartyC") {
            cordaParty = "O=PartyC,L=Los Angelos,C=US";
        }
    }
    */
    /*
    @GetMapping(value = "/cash",produces = APPLICATION_JSON_VALUE)
    public List<StateAndRef<Cash.State>> getCash() {
        // Filter by states type: Cash.
        return proxy.vaultQuery(Cash.State.class).getStates();
    }

    @GetMapping(value = "/cash-balances",produces = APPLICATION_JSON_VALUE)
    public Map<Currency,Amount<Currency>> cashBalances(){
        return getCashBalances(proxy);
    }

    @PutMapping(value =  "/issue-iou" , produces = TEXT_PLAIN_VALUE )
    public ResponseEntity<String> issueIOU(@RequestParam(value = "amount") int amount,
                                           @RequestParam(value = "currency") String currency,
                                           @RequestParam(value = "party") String party) throws IllegalArgumentException {
        // Get party objects for myself and the counterparty.
        Party me = proxy.nodeInfo().getLegalIdentities().get(0);
        Party lender = Optional.ofNullable(proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(party))).orElseThrow(() -> new IllegalArgumentException("Unknown party name."));
        // Create a new IOU states using the parameters given.
        try {
            IOUState state = new IOUState(new Amount<>((long) amount * 100, Currency.getInstance(currency)), lender, me);
            // Start the IOUIssueFlow. We block and waits for the flows to return.
            SignedTransaction result = proxy.startTrackedFlowDynamic(IOUIssueFlow.InitiatorFlow.class, state).getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction id "+ result.getId() +" committed to ledger.\n " + result.getTx().getOutput(0));
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }
    @GetMapping(value =  "transfer-iou" , produces =  TEXT_PLAIN_VALUE )
    public ResponseEntity<String> transferIOU(@RequestParam(value = "id") String id,
                                              @RequestParam(value = "party") String party) {
        UniqueIdentifier linearId = new UniqueIdentifier(null,UUID.fromString(id));
        Party newLender = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(party));
        try {
            proxy.startTrackedFlowDynamic(IOUTransferFlow.InitiatorFlow.class, linearId, newLender).getReturnValue().get();
            return ResponseEntity.status(HttpStatus.CREATED).body("IOU "+linearId.toString()+" transferred to "+party+".");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
*/
    /**
     * Settles an IOU. Requires cash in the right currency to be able to settle.
     * Example request:
     * curl -X GET 'http://localhost:10007/api/iou/settle-iou?id=705dc5c5-44da-4006-a55b-e29f78955089&amount=98&currency=USD'
     */
    /*
    @GetMapping(value =  "settle-iou" , produces = TEXT_PLAIN_VALUE )
    public  ResponseEntity<String> settleIOU(@RequestParam(value = "id") String id,
                                             @RequestParam(value = "amount") int amount,
                                             @RequestParam(value = "currency") String currency) {

        UniqueIdentifier linearId = new UniqueIdentifier(null, UUID.fromString(id));
        try {
            proxy.startTrackedFlowDynamic(IOUSettleFlow.InitiatorFlow.class, linearId,
                    new Amount<>((long) amount * 100, Currency.getInstance(currency))).getReturnValue().get();
            return ResponseEntity.status(HttpStatus.CREATED).body(""+ amount+ currency +" paid off on IOU id "+linearId.toString()+".");

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
*/
    /**
     * Helper end-point to issue some cash to ourselves.
     * Example request:
     * curl -X GET 'http://localhost:10009/api/iou/self-issue-cash?amount=100&currency=USD'
     */

    /* @GetMapping(value =  "self-issue-cash" , produces =  TEXT_PLAIN_VALUE )
    public ResponseEntity<String> selfIssueCash(@RequestParam(value = "amount") int amount,
                      @RequestParam(value = "currency") String currency) {

        try {
            Cash.State cashState = proxy.startTrackedFlowDynamic(SelfIssueCashFlow.class,
                    new Amount<>((long) amount * 100, Currency.getInstance(currency))).getReturnValue().get();
            return ResponseEntity.status(HttpStatus.CREATED).body(cashState.toString());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
    */
}
