package uni.eligibility;
import uni.eligibility.EligibilityDesk.HoldStatus;
import uni.store.CsvTable;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
public final class EligibilityDesk{
 public enum HoldStatus{
  HELD,CONFIRMED,RELEASED;
  boolean canMoveTo(HoldStatus next){return switch(this){case HELD->next!=HELD;case CONFIRMED->next==RELEASED;case RELEASED->false;};}
 }
 public static final class NotEligibleException extends RuntimeException{public NotEligibleException(String message){super(message);}}
 private final HoldGateway gateway;
 private final PrerequisiteChecker prerequisites=new PrerequisiteChecker();
 private final FinancialHoldChecker finance=new FinancialHoldChecker(50000);
 public EligibilityDesk(Path dir){gateway=new HoldGateway(dir);}
 public String placeHold(String reference,String studentId,String courseId){
  HoldGateway.Student student=gateway.student(studentId).orElseThrow(()->new NotEligibleException("unknown student "+studentId));
  List<String> required=gateway.prerequisites(courseId).orElseThrow(()->new NotEligibleException("unknown course "+courseId));
  if(student.completed().contains(courseId))throw new NotEligibleException(studentId+" has already completed "+courseId);
  if(gateway.hasActiveHold(studentId,courseId))throw new NotEligibleException(studentId+" is already registered for "+courseId);
  if(finance.blocked(student.balance()))throw new NotEligibleException("financial hold: balance "+student.balance()+" exceeds "+finance.limit());
  List<String> missing=prerequisites.missing(student.completed(),required);
  if(!missing.isEmpty())throw new NotEligibleException("missing prerequisites "+missing);
  EnrollmentHold hold=new EnrollmentHold(gateway.nextId(),reference,studentId,courseId,HoldStatus.HELD);
  gateway.save(hold);
  return hold.id();
 }
 public void confirm(String holdId){change(holdId,HoldStatus.CONFIRMED);}
 public void release(String holdId){change(holdId,HoldStatus.RELEASED);}
 private void change(String holdId,HoldStatus to){
  EnrollmentHold hold=gateway.load(holdId).orElseThrow(()->new NoSuchElementException("no hold "+holdId));
  hold.moveTo(to);
  gateway.save(hold);
 }
 public Optional<String> holdFor(String reference){return gateway.byReference(reference).map(EnrollmentHold::id);}
 public Optional<HoldStatus> statusOf(String holdId){return gateway.load(holdId).map(EnrollmentHold::status);}
 public void setBalance(String studentId,int balance){gateway.setBalance(studentId,balance);}
 public List<String> describe(){return gateway.describe();}
}
final class PrerequisiteChecker{
 List<String> missing(Set<String> completed,List<String> required){return required.stream().filter(r->!completed.contains(r)).collect(Collectors.toList());}
}
final class FinancialHoldChecker{
 private final int limit;
 FinancialHoldChecker(int limit){this.limit=limit;}
 int limit(){return limit;}
 boolean blocked(int balance){return balance>limit;}
}
final class EnrollmentHold{
 private final String id,reference,studentId,courseId;
 private HoldStatus status;
 EnrollmentHold(String id,String reference,String studentId,String courseId,HoldStatus status){this.id=id;this.reference=reference;this.studentId=studentId;this.courseId=courseId;this.status=status;}
 String id(){return id;}
 String reference(){return reference;}
 String studentId(){return studentId;}
 String courseId(){return courseId;}
 HoldStatus status(){return status;}
 void moveTo(HoldStatus next){
  if(next==status)return;
  if(!status.canMoveTo(next))throw new IllegalStateException("hold "+id+": "+status+" -> "+next+" is not allowed");
  status=next;
 }
}
final class HoldGateway{
 record Student(String id,String name,Set<String> completed,int balance){}
 private final CsvTable students,catalog,holds;
 HoldGateway(Path dir){
  students=new CsvTable(dir.resolve("a_students.csv"));
  catalog=new CsvTable(dir.resolve("a_prerequisites.csv"));
  holds=new CsvTable(dir.resolve("a_holds.csv"));
  students.seedIfEmpty("S1,Aigerim,CS101;CS201;MATH101,0","S2,Dias,CS101;MATH101,0","S3,Madina,CS101;MATH101,80000","S4,Timur,,0");
  catalog.seedIfEmpty("CS101,","MATH101,","CS201,CS101","CS301,CS201","MATH201,MATH101");
 }
 Optional<Student> student(String id){return students.find(id).map(r->new Student(r[0],r[1],items(r[2]),Integer.parseInt(r[3])));}
 Optional<List<String>> prerequisites(String courseId){return catalog.find(courseId).map(r->new ArrayList<>(items(r[1])));}
 void setBalance(String id,int balance){
  Student s=student(id).orElseThrow(()->new NoSuchElementException("unknown student "+id));
  students.upsert(s.id(),s.name(),String.join(";",s.completed()),String.valueOf(balance));
 }
 String nextId(){return "H"+(holds.rows().size()+1);}
 void save(EnrollmentHold h){holds.upsert(h.id(),h.reference(),h.studentId(),h.courseId(),h.status().name());}
 Optional<EnrollmentHold> load(String id){return holds.find(id).map(HoldGateway::hold);}
 Optional<EnrollmentHold> byReference(String reference){return holds.rows().stream().filter(r->r[1].equals(reference)).findFirst().map(HoldGateway::hold);}
 boolean hasActiveHold(String studentId,String courseId){return holds.rows().stream().anyMatch(r->r[2].equals(studentId)&&r[3].equals(courseId)&&!r[4].equals("RELEASED"));}
 List<String> describe(){
  List<String> out=new ArrayList<>();
  students.rows().forEach(r->out.add("student "+r[0]+" "+r[1]+" completed=["+r[2]+"] balance="+r[3]));
  holds.rows().forEach(r->out.add("hold "+r[0]+" ref="+r[1]+" "+r[2]+"->"+r[3]+" "+r[4]));
  return out;
 }
 private static EnrollmentHold hold(String[] r){return new EnrollmentHold(r[0],r[1],r[2],r[3],HoldStatus.valueOf(r[4]));}
 private static Set<String> items(String v){return Arrays.stream(v.split(";")).filter(s->!s.isEmpty()).collect(Collectors.toCollection(TreeSet::new));}
}
