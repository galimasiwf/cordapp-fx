package com.bootcamp.contracts;

import com.fxsettlement.states.FXTradeState;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.testing.core.TestIdentity;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StateTests {
    private final Party alice = new TestIdentity(new CordaX500Name("Alice", "", "GB")).getParty();
    private final Party bob = new TestIdentity(new CordaX500Name("Bob", "", "GB")).getParty();

    @Test
    public void tokenStateHasIssuerOwnerAndAmountParamsOfCorrectTypeInConstructor() {
        new FXTradeState(alice, bob, 100,80000, "USD", "INR",  "03172023");
    }

    @Test
    public void tokenStateHasGettersForIssuerOwnerAndAmount() {
        FXTradeState FXTradeState = new FXTradeState(alice, bob,100,80000,"USD","INR","03172023");
        assertEquals(alice, FXTradeState.getIssuer());
        assertEquals(bob, FXTradeState.getOwner());
        assertEquals(100, FXTradeState.getBuyamount());
        assertEquals(80000, FXTradeState.getSellamount());
        assertEquals("USD", FXTradeState.getBuycurrency());
        assertEquals("INR", FXTradeState.getSellcurrency());
        assertEquals("03172023", FXTradeState.getSettledate());
    }

    @Test
    public void tokenStateImplementsContractState() {
        //assertTrue(new FXTradeState(alice, bob, 100, 80000,"USD", "INR","03172023") );
    }

    @Test
    public void tokenStateHasTwoParticipantsTheIssuerAndTheOwner() {
        FXTradeState FXTradeState = new FXTradeState(alice, bob,100,80000,"USD","INR","03172023");
        assertEquals(2, FXTradeState.getParticipants().size());
        assertTrue(FXTradeState.getParticipants().contains(alice));
        assertTrue(FXTradeState.getParticipants().contains(bob));
    }
}