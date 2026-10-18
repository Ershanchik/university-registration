package uni.facade;
import uni.billing.BillingDesk;
import uni.eligibility.EligibilityDesk;
import uni.facade.ChainLedger.Chain;
import uni.facade.ChainLedger.State;
import uni.seating.SeatingDesk;
import java.util.*;
public final class RegistrarAdminFacade{
 private final EligibilityDesk eligibility;
 private final SeatingDesk seating;
 private final BillingDesk billing;
 private final ChainLedger ledger;
 private final ChainUnwinder unwinder;
 public RegistrarAdminFacade(EligibilityDesk eligibility,SeatingDesk seating,BillingDesk billing,ChainLedger ledger){
  this.eligibility=eligibility;this.seating=seating;this.billing=billing;this.ledger=ledger;
  unwinder=new ChainUnwinder(eligibility,seating,billing,ledger);
 }
 public List<String> snapshot(){
  List<String> out=new ArrayList<>();
  out.add("== chains ==");
  ledger.all().forEach(c->out.add(c.id()+" "+c.studentId()+"->"+c.courseId()+" "+c.state()+" ("+c.detail()+")"));
  out.add("== A: eligibility ==");
  out.addAll(eligibility.describe());
  out.add("== B: seating ==");
  out.addAll(seating.describe());
  out.add("== C: billing ==");
  out.addAll(billing.describe());
  return out;
 }
 public Chain compensate(String chainId){
  RegistrationException.InvalidRequest.requireId("chain id",chainId);
  Chain chain=ledger.find(chainId).orElseThrow(()->new RegistrationException.InvalidRequest("unknown chain "+chainId));
  if(chain.state()==State.COMPENSATED||chain.state()==State.REJECTED)throw new RegistrationException.InvalidRequest("chain "+chainId+" is already "+chain.state());
  return unwinder.unwind(chain,"manual compensation of "+chain.state()+" chain");
 }
 public void setCapacity(String courseId,int seats){
  RegistrationException.InvalidRequest.requireId("course id",courseId);
  if(seats<0)throw new RegistrationException.InvalidRequest("capacity must not be negative");
  seating.setCapacity(courseId,seats);
 }
 public void setCreditLimit(String studentId,int limit){
  RegistrationException.InvalidRequest.requireId("student id",studentId);
  if(limit<0)throw new RegistrationException.InvalidRequest("credit limit must not be negative");
  billing.setLimit(studentId,limit);
 }
 public void setBalance(String studentId,int balance){
  RegistrationException.InvalidRequest.requireId("student id",studentId);
  if(balance<0)throw new RegistrationException.InvalidRequest("balance must not be negative");
  eligibility.setBalance(studentId,balance);
 }
}
