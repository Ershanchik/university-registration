package uni.facade;
import uni.billing.BillingDesk;
import uni.eligibility.EligibilityDesk;
import uni.seating.SeatingDesk;
import uni.store.CsvTable;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
public final class ChainLedger{
 public enum State{
  PENDING_A,PENDING_B,PENDING_C,COMPLETED,COMPENSATED,REJECTED;
  public boolean isOpen(){return ordinal()<=PENDING_C.ordinal();}
 }
 public record Chain(String id,String studentId,String courseId,State state,String detail){}
 private final CsvTable table;
 public ChainLedger(Path dir){table=new CsvTable(dir.resolve("chains.csv"));}
 public Chain open(String studentId,String courseId){return save(new Chain("C"+(table.rows().size()+1),studentId,courseId,State.PENDING_A,"started"));}
 public Chain move(Chain chain,State state,String detail){return save(new Chain(chain.id(),chain.studentId(),chain.courseId(),state,detail));}
 public Optional<Chain> find(String id){return table.find(id).map(ChainLedger::chain);}
 public List<Chain> all(){return table.rows().stream().map(ChainLedger::chain).collect(Collectors.toList());}
 public List<Chain> unfinished(){return all().stream().filter(c->c.state().isOpen()).collect(Collectors.toList());}
 private Chain save(Chain c){table.upsert(c.id(),c.studentId(),c.courseId(),c.state().name(),c.detail());return c;}
 private static Chain chain(String[] r){return new Chain(r[0],r[1],r[2],State.valueOf(r[3]),r[4]);}
}
final class ChainUnwinder{
 private final EligibilityDesk eligibility;
 private final SeatingDesk seating;
 private final BillingDesk billing;
 private final ChainLedger ledger;
 ChainUnwinder(EligibilityDesk eligibility,SeatingDesk seating,BillingDesk billing,ChainLedger ledger){this.eligibility=eligibility;this.seating=seating;this.billing=billing;this.ledger=ledger;}
 ChainLedger.Chain unwind(ChainLedger.Chain chain,String reason){
  List<String> undone=new ArrayList<>();
  billing.invoiceFor(chain.id()).ifPresent(i->{billing.voidInvoice(i);undone.add("invoice "+i+" voided");});
  seating.seatFor(chain.id()).ifPresent(s->{seating.release(s);undone.add("seat "+s+" released");});
  eligibility.holdFor(chain.id()).ifPresent(h->{eligibility.release(h);undone.add("hold "+h+" released");});
  return ledger.move(chain,ChainLedger.State.COMPENSATED,reason+(undone.isEmpty()?"":" | undone: "+String.join(" then ",undone)));
 }
}
