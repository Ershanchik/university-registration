package uni.billing;
import uni.billing.BillingDesk.InvoiceStatus;
import uni.store.CsvTable;
import java.nio.file.Path;
import java.util.*;
public final class BillingDesk{
 public enum InvoiceStatus{
  ISSUED,PAID,VOIDED;
  boolean canMoveTo(InvoiceStatus next){return switch(this){case ISSUED->next!=ISSUED;case PAID->next==VOIDED;case VOIDED->false;};}
 }
 public static final class PaymentDeclinedException extends RuntimeException{public PaymentDeclinedException(String message){super(message);}}
 private final BillingGateway gateway;
 private final TuitionPolicy policy=new TuitionPolicy(25000);
 private final TranscriptService transcript;
 public BillingDesk(Path dir){gateway=new BillingGateway(dir);transcript=new TranscriptService(dir);}
 public String bill(String reference,String studentId,String courseId){
  int credits=gateway.credits(courseId).orElseThrow(()->new PaymentDeclinedException("no tariff for course "+courseId));
  int limit=gateway.limit(studentId).orElseThrow(()->new PaymentDeclinedException("no billing account for "+studentId));
  int amount=policy.tuition(credits);
  if(!policy.affordable(amount,limit))throw new PaymentDeclinedException("tuition "+amount+" exceeds credit limit "+limit+" of "+studentId);
  Invoice invoice=new Invoice(gateway.nextId(),reference,studentId,courseId,amount,InvoiceStatus.ISSUED);
  gateway.save(invoice);
  return invoice.id();
 }
 public void settle(String invoiceId){
  Invoice invoice=change(invoiceId,InvoiceStatus.PAID);
  transcript.record(invoice.id(),invoice.studentId(),invoice.courseId());
 }
 public void voidInvoice(String invoiceId){
  Invoice invoice=change(invoiceId,InvoiceStatus.VOIDED);
  transcript.withdraw(invoice.id());
 }
 private Invoice change(String invoiceId,InvoiceStatus to){
  Invoice invoice=gateway.load(invoiceId).orElseThrow(()->new NoSuchElementException("no invoice "+invoiceId));
  invoice.moveTo(to);
  gateway.save(invoice);
  return invoice;
 }
 public Optional<String> invoiceFor(String reference){return gateway.byReference(reference).map(Invoice::id);}
 public Optional<InvoiceStatus> statusOf(String invoiceId){return gateway.load(invoiceId).map(Invoice::status);}
 public void setLimit(String studentId,int limit){gateway.setLimit(studentId,limit);}
 public List<String> describe(){List<String> out=gateway.describe();out.addAll(transcript.describe());return out;}
}
final class TuitionPolicy{
 private final int ratePerCredit;
 TuitionPolicy(int ratePerCredit){this.ratePerCredit=ratePerCredit;}
 int tuition(int credits){return credits*ratePerCredit;}
 boolean affordable(int amount,int limit){return amount<=limit;}
}
final class Invoice{
 private final String id,reference,studentId,courseId;
 private final int amount;
 private InvoiceStatus status;
 Invoice(String id,String reference,String studentId,String courseId,int amount,InvoiceStatus status){this.id=id;this.reference=reference;this.studentId=studentId;this.courseId=courseId;this.amount=amount;this.status=status;}
 String id(){return id;}
 String reference(){return reference;}
 String studentId(){return studentId;}
 String courseId(){return courseId;}
 int amount(){return amount;}
 InvoiceStatus status(){return status;}
 void moveTo(InvoiceStatus next){
  if(next==status)return;
  if(!status.canMoveTo(next))throw new IllegalStateException("invoice "+id+": "+status+" -> "+next+" is not allowed");
  status=next;
 }
}
final class TranscriptService{
 private final CsvTable table;
 TranscriptService(Path dir){table=new CsvTable(dir.resolve("c_transcript.csv"));}
 void record(String invoiceId,String studentId,String courseId){table.upsert(invoiceId,studentId,courseId,"ENROLLED");}
 void withdraw(String invoiceId){table.find(invoiceId).ifPresent(r->table.upsert(r[0],r[1],r[2],"WITHDRAWN"));}
 List<String> describe(){List<String> out=new ArrayList<>();table.rows().forEach(r->out.add("transcript "+r[1]+" "+r[2]+" "+r[3]+" (invoice "+r[0]+")"));return out;}
}
final class BillingGateway{
 private final CsvTable accounts,tariffs,invoices;
 BillingGateway(Path dir){
  accounts=new CsvTable(dir.resolve("c_accounts.csv"));
  tariffs=new CsvTable(dir.resolve("c_tariffs.csv"));
  invoices=new CsvTable(dir.resolve("c_invoices.csv"));
  accounts.seedIfEmpty("S1,500000","S2,90000","S3,500000","S4,500000");
  tariffs.seedIfEmpty("CS101,3","CS201,4","CS301,4","MATH101,3","MATH201,3");
 }
 Optional<Integer> credits(String courseId){return tariffs.find(courseId).map(r->Integer.parseInt(r[1]));}
 Optional<Integer> limit(String studentId){return accounts.find(studentId).map(r->Integer.parseInt(r[1]));}
 void setLimit(String studentId,int limit){
  if(accounts.find(studentId).isEmpty())throw new NoSuchElementException("no billing account for "+studentId);
  accounts.upsert(studentId,String.valueOf(limit));
 }
 String nextId(){return "I"+(invoices.rows().size()+1);}
 void save(Invoice i){invoices.upsert(i.id(),i.reference(),i.studentId(),i.courseId(),String.valueOf(i.amount()),i.status().name());}
 Optional<Invoice> load(String id){return invoices.find(id).map(BillingGateway::invoice);}
 Optional<Invoice> byReference(String reference){return invoices.rows().stream().filter(r->r[1].equals(reference)).findFirst().map(BillingGateway::invoice);}
 List<String> describe(){
  List<String> out=new ArrayList<>();
  accounts.rows().forEach(r->out.add("account "+r[0]+" credit limit="+r[1]));
  invoices.rows().forEach(r->out.add("invoice "+r[0]+" ref="+r[1]+" "+r[2]+"->"+r[3]+" amount="+r[4]+" "+r[5]));
  return out;
 }
 private static Invoice invoice(String[] r){return new Invoice(r[0],r[1],r[2],r[3],Integer.parseInt(r[4]),InvoiceStatus.valueOf(r[5]));}
}
