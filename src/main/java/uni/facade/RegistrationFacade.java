package uni.facade;
import uni.billing.BillingDesk;
import uni.eligibility.EligibilityDesk;
import uni.facade.ChainLedger.Chain;
import uni.facade.ChainLedger.State;
import uni.seating.SeatingDesk;
import java.util.*;
public final class RegistrationFacade{
 private final EligibilityDesk eligibility;
 private final SeatingDesk seating;
 private final BillingDesk billing;
 private final ChainLedger ledger;
 private final ChainUnwinder unwinder;
 public RegistrationFacade(EligibilityDesk eligibility,SeatingDesk seating,BillingDesk billing,ChainLedger ledger){
  this.eligibility=eligibility;this.seating=seating;this.billing=billing;this.ledger=ledger;
  unwinder=new ChainUnwinder(eligibility,seating,billing,ledger);
 }
 public Chain register(String studentId,String courseId){
  RegistrationException.InvalidRequest.requireId("student id",studentId);
  RegistrationException.InvalidRequest.requireId("course id",courseId);
  Chain chain=ledger.open(studentId,courseId);
  String hold;
  try{hold=eligibility.placeHold(chain.id(),studentId,courseId);}
  catch(EligibilityDesk.NotEligibleException e){throw new RegistrationException.StepFailed("A",e,ledger.move(chain,State.REJECTED,e.getMessage()));}
  catch(RuntimeException e){throw new RegistrationException.StepFailed("A",e,unwinder.unwind(chain,"A failed: "+e.getMessage()));}
  chain=ledger.move(chain,State.PENDING_B,"hold "+hold+" placed");
  String seat;
  try{seat=seating.reserve(chain.id(),studentId,courseId);}
  catch(RuntimeException e){throw new RegistrationException.StepFailed("B",e,unwinder.unwind(chain,"B failed: "+e.getMessage()));}
  chain=ledger.move(chain,State.PENDING_C,"seat "+seat+" reserved");
  String invoice;
  try{invoice=billing.bill(chain.id(),studentId,courseId);}
  catch(RuntimeException e){throw new RegistrationException.StepFailed("C",e,unwinder.unwind(chain,"C failed: "+e.getMessage()));}
  return complete(chain,hold,seat,invoice);
 }
 public List<Chain> recover(){
  List<Chain> resolved=new ArrayList<>();
  for(Chain chain:ledger.unfinished())resolved.add(resume(chain));
  return resolved;
 }
 private Chain resume(Chain chain){
  Optional<String> hold=eligibility.holdFor(chain.id());
  Optional<String> seat=seating.seatFor(chain.id());
  Optional<String> invoice=billing.invoiceFor(chain.id()).filter(i->billing.statusOf(i).filter(s->s!=BillingDesk.InvoiceStatus.VOIDED).isPresent());
  if(hold.isPresent()&&seat.isPresent()&&invoice.isPresent())return complete(chain,hold.get(),seat.get(),invoice.get());
  return unwinder.unwind(chain,"recovered after restart, chain was "+chain.state());
 }
 private Chain complete(Chain chain,String hold,String seat,String invoice){
  seating.confirm(seat);
  eligibility.confirm(hold);
  billing.settle(invoice);
  return ledger.move(chain,State.COMPLETED,"hold "+hold+" seat "+seat+" invoice "+invoice+" confirmed");
 }
}
