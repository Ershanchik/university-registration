package uni;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uni.billing.BillingDesk;
import uni.billing.BillingDesk.InvoiceStatus;
import uni.eligibility.EligibilityDesk;
import uni.eligibility.EligibilityDesk.HoldStatus;
import uni.facade.*;
import uni.facade.ChainLedger.State;
import uni.seating.SeatingDesk;
import uni.seating.SeatingDesk.SeatStatus;
import uni.store.CsvTable;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;
class RegistrationTest{
 @TempDir Path dir;
 Campus campus;
 @BeforeEach void openCampus(){campus=new Campus(dir);}
 static final class Campus{
  final Path dir;
  EligibilityDesk a;SeatingDesk b;BillingDesk c;ChainLedger ledger;RegistrationFacade facade;RegistrarAdminFacade admin;
  Campus(Path dir){this.dir=dir;restart();}
  void restart(){
   a=new EligibilityDesk(dir);b=new SeatingDesk(dir);c=new BillingDesk(dir);ledger=new ChainLedger(dir);
   facade=new RegistrationFacade(a,b,c,ledger);admin=new RegistrarAdminFacade(a,b,c,ledger);
  }
  Campus courseIsFull(String course){admin.setCapacity(course,0);return this;}
  Campus billingLimitOf(String student,int limit){admin.setCreditLimit(student,limit);return this;}
  Outcome registers(String student,String course){
   try{return new Outcome(this,facade.register(student,course),null);}
   catch(RegistrationException.StepFailed e){return new Outcome(this,e.chain(),e.stage());}
  }
  Outcome outcomeOf(ChainLedger.Chain chain){return new Outcome(this,chain,null);}
  ChainLedger.Chain crashedAfterA(String student,String course){
   ChainLedger.Chain chain=ledger.open(student,course);
   a.placeHold(chain.id(),student,course);
   return ledger.move(chain,State.PENDING_B,"simulated crash");
  }
  ChainLedger.Chain crashedAfterB(String student,String course){
   ChainLedger.Chain chain=crashedAfterA(student,course);
   b.reserve(chain.id(),student,course);
   return ledger.move(chain,State.PENDING_C,"simulated crash");
  }
  ChainLedger.Chain crashedAfterC(String student,String course){
   ChainLedger.Chain chain=crashedAfterB(student,course);
   c.bill(chain.id(),student,course);
   return chain;
  }
 }
 static final class Outcome{
  final Campus campus;final ChainLedger.Chain chain;final String failedStage;
  Outcome(Campus campus,ChainLedger.Chain chain,String failedStage){this.campus=campus;this.chain=chain;this.failedStage=failedStage;}
  Outcome succeeded(){assertNull(failedStage);return this;}
  Outcome failedAt(String stage){assertEquals(stage,failedStage);return this;}
  Outcome because(String fragment){assertTrue(chain.detail().contains(fragment),chain.detail());return this;}
  Outcome chainIs(State state){assertEquals(state,campus.ledger.find(chain.id()).orElseThrow().state());return this;}
  Outcome holdIs(HoldStatus status){assertEquals(Optional.of(status),campus.a.holdFor(chain.id()).flatMap(campus.a::statusOf));return this;}
  Outcome seatIs(SeatStatus status){assertEquals(Optional.of(status),campus.b.seatFor(chain.id()).flatMap(campus.b::statusOf));return this;}
  Outcome invoiceIs(InvoiceStatus status){assertEquals(Optional.of(status),campus.c.invoiceFor(chain.id()).flatMap(campus.c::statusOf));return this;}
  Outcome noHold(){assertTrue(campus.a.holdFor(chain.id()).isEmpty());return this;}
  Outcome noSeat(){assertTrue(campus.b.seatFor(chain.id()).isEmpty());return this;}
  Outcome noInvoice(){assertTrue(campus.c.invoiceFor(chain.id()).isEmpty());return this;}
  Outcome undoneInOrder(String first,String second){
   String d=campus.ledger.find(chain.id()).orElseThrow().detail();
   assertTrue(d.contains(first)&&d.contains(second)&&d.indexOf(first)<d.indexOf(second),d);
   return this;
  }
 }
 @Test void eligibilityDeskWorksWithoutTheOtherSubsystems(){
  String hold=campus.a.placeHold("X1","S1","MATH201");
  assertEquals(Optional.of(HoldStatus.HELD),campus.a.statusOf(hold));
  campus.a.confirm(hold);
  campus.a.release(hold);
  assertEquals(Optional.of(HoldStatus.RELEASED),campus.a.statusOf(hold));
  assertThrows(IllegalStateException.class,()->campus.a.confirm(hold));
  assertDoesNotThrow(()->campus.a.placeHold("X2","S1","MATH201"));
  assertThrows(EligibilityDesk.NotEligibleException.class,()->campus.a.placeHold("X3","S1","MATH201"));
  assertThrows(EligibilityDesk.NotEligibleException.class,()->campus.a.placeHold("X4","S4","CS201"));
  assertThrows(EligibilityDesk.NotEligibleException.class,()->campus.a.placeHold("X5","S1","CS101"));
  campus.a.setBalance("S3",0);
  assertDoesNotThrow(()->campus.a.placeHold("X6","S3","CS201"));
  assertThrows(NoSuchElementException.class,()->campus.a.confirm("H99"));
 }
 @Test void seatingDeskReservesWaitlistsAndReleases(){
  campus.b.setCapacity("CS201",1);
  String seat=campus.b.reserve("X1","S1","CS201");
  assertTrue(assertThrows(SeatingDesk.SeatUnavailableException.class,()->campus.b.reserve("X2","S1","CS201")).getMessage().contains("already has a seat"));
  assertTrue(assertThrows(SeatingDesk.SeatUnavailableException.class,()->campus.b.reserve("X3","S2","CS201")).getMessage().contains("waitlisted at position 1"));
  assertTrue(assertThrows(SeatingDesk.SeatUnavailableException.class,()->campus.b.reserve("X4","S2","CS201")).getMessage().contains("already waitlisted at position 1"));
  assertTrue(assertThrows(SeatingDesk.SeatUnavailableException.class,()->campus.b.reserve("X5","S3","CS201")).getMessage().contains("waitlisted at position 2"));
  assertTrue(assertThrows(SeatingDesk.SeatUnavailableException.class,()->campus.b.reserve("X6","S4","CS201")).getMessage().contains("waitlist is full"));
  campus.b.confirm(seat);
  campus.b.release(seat);
  assertThrows(IllegalStateException.class,()->campus.b.confirm(seat));
  assertDoesNotThrow(()->campus.b.reserve("X7","S2","CS201"));
  assertThrows(SeatingDesk.SeatUnavailableException.class,()->campus.b.reserve("X8","S1","NOPE"));
  assertThrows(NoSuchElementException.class,()->campus.b.setCapacity("NOPE",1));
 }
 @Test void billingDeskIssuesSettlesVoidsAndDeclines(){
  String invoice=campus.c.bill("X1","S1","MATH201");
  assertEquals(Optional.of(InvoiceStatus.ISSUED),campus.c.statusOf(invoice));
  campus.c.settle(invoice);
  assertEquals(Optional.of(InvoiceStatus.PAID),campus.c.statusOf(invoice));
  campus.c.voidInvoice(invoice);
  assertEquals(Optional.of(InvoiceStatus.VOIDED),campus.c.statusOf(invoice));
  assertThrows(IllegalStateException.class,()->campus.c.settle(invoice));
  assertThrows(BillingDesk.PaymentDeclinedException.class,()->campus.c.bill("X2","S2","CS201"));
  assertThrows(BillingDesk.PaymentDeclinedException.class,()->campus.c.bill("X3","S1","NOPE"));
  assertThrows(BillingDesk.PaymentDeclinedException.class,()->campus.c.bill("X4","S9","CS101"));
  assertThrows(NoSuchElementException.class,()->campus.c.setLimit("S9",1));
  assertThrows(NoSuchElementException.class,()->campus.c.settle("I99"));
 }
 @Test void successfulRegistrationConfirmsEveryStage(){
  campus.registers("S1","MATH201").succeeded().chainIs(State.COMPLETED).holdIs(HoldStatus.CONFIRMED).seatIs(SeatStatus.CONFIRMED).invoiceIs(InvoiceStatus.PAID);
 }
 @Test void failureInBCompensatesOnlyA(){
  campus.registers("S1","CS301").failedAt("B").because("waitlisted").chainIs(State.COMPENSATED).holdIs(HoldStatus.RELEASED).noSeat().noInvoice();
 }
 @Test void failureInCCompensatesBThenA(){
  campus.registers("S2","CS201").failedAt("C").because("exceeds credit limit").chainIs(State.COMPENSATED).seatIs(SeatStatus.RELEASED).holdIs(HoldStatus.RELEASED).noInvoice().undoneInOrder("seat","hold");
 }
 @Test void financialHoldRejectsBeforeAnythingIsCommitted(){
  campus.registers("S3","CS201").failedAt("A").because("financial hold").chainIs(State.REJECTED).noHold().noSeat().noInvoice();
 }
 @Test void missingPrerequisiteAndUnknownStudentAreRejectedAtA(){
  campus.registers("S4","CS201").failedAt("A").because("missing prerequisites").chainIs(State.REJECTED).noHold();
  campus.registers("S9","CS101").failedAt("A").because("unknown student").chainIs(State.REJECTED);
 }
 @Test void invalidInputIsRejectedBeforeAChainIsOpened(){
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.facade.register("S 1","CS101"));
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.facade.register("S1",null));
  assertTrue(campus.ledger.all().isEmpty());
 }
 @Test void duplicateRegistrationIsRejected(){
  campus.registers("S1","MATH201").succeeded();
  campus.registers("S1","MATH201").failedAt("A").because("already registered").noSeat().noInvoice();
 }
 @Test void adminTuningMakesBFailureReachableOnDemand(){
  campus.courseIsFull("MATH201").registers("S1","MATH201").failedAt("B").chainIs(State.COMPENSATED).holdIs(HoldStatus.RELEASED);
 }
 @Test void adminTuningMakesCFailureReachableOnDemand(){
  campus.billingLimitOf("S1",1000).registers("S1","MATH201").failedAt("C").chainIs(State.COMPENSATED).seatIs(SeatStatus.RELEASED).holdIs(HoldStatus.RELEASED);
 }
 @Test void adminRejectsNegativeAndUnknownValues(){
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.admin.setCapacity("CS101",-1));
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.admin.setCreditLimit("S1",-1));
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.admin.setBalance("S1",-1));
  assertDoesNotThrow(()->campus.admin.setBalance("S1",10));
  assertThrows(NoSuchElementException.class,()->campus.admin.setCreditLimit("S9",1));
 }
 @Test void committedStateSurvivesRestart(){
  Outcome outcome=campus.registers("S1","MATH201").succeeded();
  campus.restart();
  outcome.chainIs(State.COMPLETED).holdIs(HoldStatus.CONFIRMED).seatIs(SeatStatus.CONFIRMED).invoiceIs(InvoiceStatus.PAID);
  assertEquals(1,campus.ledger.all().size());
  assertTrue(campus.facade.recover().isEmpty());
 }
 @Test void restartAfterAOnlyReleasesTheHold(){
  ChainLedger.Chain chain=campus.crashedAfterA("S1","MATH201");
  campus.restart();
  assertEquals(1,campus.facade.recover().size());
  campus.outcomeOf(chain).chainIs(State.COMPENSATED).holdIs(HoldStatus.RELEASED).noSeat().noInvoice();
 }
 @Test void restartAfterBRollsBackBThenA(){
  ChainLedger.Chain chain=campus.crashedAfterB("S2","CS201");
  campus.restart();
  campus.facade.recover();
  campus.outcomeOf(chain).chainIs(State.COMPENSATED).seatIs(SeatStatus.RELEASED).holdIs(HoldStatus.RELEASED).noInvoice().undoneInOrder("seat","hold");
  assertTrue(campus.facade.recover().isEmpty());
 }
 @Test void restartAfterCRollsTheChainForward(){
  ChainLedger.Chain chain=campus.crashedAfterC("S1","MATH201");
  campus.restart();
  campus.facade.recover();
  campus.outcomeOf(chain).chainIs(State.COMPLETED).holdIs(HoldStatus.CONFIRMED).seatIs(SeatStatus.CONFIRMED).invoiceIs(InvoiceStatus.PAID);
 }
 @Test void restartWhenLedgerWasNotUpdatedAfterAStillFindsTheHold(){
  ChainLedger.Chain chain=campus.ledger.open("S1","MATH201");
  campus.a.placeHold(chain.id(),"S1","MATH201");
  campus.restart();
  campus.facade.recover();
  campus.outcomeOf(chain).chainIs(State.COMPENSATED).holdIs(HoldStatus.RELEASED);
 }
 @Test void restartAfterInterruptedCompensationFinishesIt(){
  ChainLedger.Chain chain=campus.crashedAfterC("S1","MATH201");
  campus.c.voidInvoice(campus.c.invoiceFor(chain.id()).orElseThrow());
  campus.restart();
  campus.facade.recover();
  campus.outcomeOf(chain).chainIs(State.COMPENSATED).invoiceIs(InvoiceStatus.VOIDED).seatIs(SeatStatus.RELEASED).holdIs(HoldStatus.RELEASED);
 }
 @Test void adminManualCompensationWithdrawsACompletedRegistration(){
  Outcome outcome=campus.registers("S1","MATH201").succeeded();
  campus.admin.compensate(outcome.chain.id());
  outcome.chainIs(State.COMPENSATED).holdIs(HoldStatus.RELEASED).seatIs(SeatStatus.RELEASED).invoiceIs(InvoiceStatus.VOIDED).undoneInOrder("invoice","seat").undoneInOrder("seat","hold");
  assertTrue(campus.admin.snapshot().stream().anyMatch(l->l.contains("WITHDRAWN")));
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.admin.compensate(outcome.chain.id()));
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.admin.compensate("C999"));
  assertThrows(RegistrationException.InvalidRequest.class,()->campus.admin.compensate("bad id"));
 }
 @Test void adminSnapshotShowsPersistedStateOfAllSubsystems(){
  campus.registers("S1","MATH201").succeeded();
  String text=String.join("\n",campus.admin.snapshot());
  for(String part:List.of("== chains ==","COMPLETED","== A: eligibility ==","CONFIRMED","== B: seating ==","== C: billing ==","PAID","ENROLLED"))assertTrue(text.contains(part),part);
 }
 @Test void csvTableKeepsRowsAfterReopenAndSanitizesSeparators(){
  new CsvTable(dir.resolve("t.csv")).append("k","a,b");
  assertArrayEquals(new String[]{"k","a;b"},new CsvTable(dir.resolve("t.csv")).find("k").orElseThrow());
 }
 @Test void scriptedConsoleSessionShowsBothCompensations()throws Exception{
  String script=String.join("\n","3","4","5","2","6","C3","1","S1","CS101","7","1","CS101","5","8","9","7","3","S1","abc","0")+"\n";
  ByteArrayOutputStream buffer=new ByteArrayOutputStream();
  Main.run(new Scanner(script),new PrintStream(buffer),dir);
  String out=buffer.toString();
  for(String part:List.of("FAILED at stage B","FAILED at stage C","OK: C3","manual compensation","REJECTED","updated","persisted state reset","unknown option","error:"))assertTrue(out.contains(part),part);
  Main.run(new Scanner("5\n0\n"),new PrintStream(new ByteArrayOutputStream()),dir);
  ChainLedger.Chain pending=campus.ledger.open("S1","MATH201");
  ByteArrayOutputStream second=new ByteArrayOutputStream();
  Main.run(new Scanner("0\n"),new PrintStream(second),dir);
  assertTrue(second.toString().contains("recovered after restart: "+pending.id()));
 }
}
