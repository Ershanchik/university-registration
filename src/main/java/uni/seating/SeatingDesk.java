package uni.seating;
import uni.seating.SeatingDesk.SeatStatus;
import uni.store.CsvTable;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
public final class SeatingDesk{
 public enum SeatStatus{
  RESERVED,CONFIRMED,RELEASED;
  boolean canMoveTo(SeatStatus next){return switch(this){case RESERVED->next!=RESERVED;case CONFIRMED->next==RELEASED;case RELEASED->false;};}
 }
 public static final class SeatUnavailableException extends RuntimeException{public SeatUnavailableException(String message){super(message);}}
 private final SeatGateway gateway;
 private final SeatAllocator allocator=new SeatAllocator();
 private final WaitlistManager waitlist=new WaitlistManager(2);
 public SeatingDesk(Path dir){gateway=new SeatGateway(dir);}
 public String reserve(String reference,String studentId,String courseId){
  int capacity=gateway.capacity(courseId).orElseThrow(()->new SeatUnavailableException("unknown course "+courseId));
  if(gateway.hasLiveSeat(studentId,courseId))throw new SeatUnavailableException(studentId+" already has a seat in "+courseId);
  if(!allocator.hasRoom(capacity,gateway.taken(courseId))){
   List<String> queue=gateway.waitlist(courseId);
   if(queue.contains(studentId))throw new SeatUnavailableException("course "+courseId+" is full, "+studentId+" is already waitlisted at position "+waitlist.positionOf(queue,studentId));
   if(waitlist.isFull(queue))throw new SeatUnavailableException("course "+courseId+" is full and its waitlist is full");
   gateway.enqueue(courseId,studentId);
   throw new SeatUnavailableException("course "+courseId+" is full, "+studentId+" is waitlisted at position "+(queue.size()+1));
  }
  SeatReservation seat=new SeatReservation(gateway.nextId(),reference,studentId,courseId,SeatStatus.RESERVED);
  gateway.save(seat);
  return seat.id();
 }
 public void confirm(String seatId){change(seatId,SeatStatus.CONFIRMED);}
 public void release(String seatId){change(seatId,SeatStatus.RELEASED);}
 private void change(String seatId,SeatStatus to){
  SeatReservation seat=gateway.load(seatId).orElseThrow(()->new NoSuchElementException("no seat "+seatId));
  seat.moveTo(to);
  gateway.save(seat);
 }
 public Optional<String> seatFor(String reference){return gateway.byReference(reference).map(SeatReservation::id);}
 public Optional<SeatStatus> statusOf(String seatId){return gateway.load(seatId).map(SeatReservation::status);}
 public void setCapacity(String courseId,int capacity){gateway.setCapacity(courseId,capacity);}
 public List<String> describe(){return gateway.describe();}
}
final class SeatAllocator{
 boolean hasRoom(int capacity,long taken){return taken<capacity;}
}
final class WaitlistManager{
 private final int max;
 WaitlistManager(int max){this.max=max;}
 boolean isFull(List<String> queue){return queue.size()>=max;}
 int positionOf(List<String> queue,String studentId){return queue.indexOf(studentId)+1;}
}
final class SeatReservation{
 private final String id,reference,studentId,courseId;
 private SeatStatus status;
 SeatReservation(String id,String reference,String studentId,String courseId,SeatStatus status){this.id=id;this.reference=reference;this.studentId=studentId;this.courseId=courseId;this.status=status;}
 String id(){return id;}
 String reference(){return reference;}
 String studentId(){return studentId;}
 String courseId(){return courseId;}
 SeatStatus status(){return status;}
 void moveTo(SeatStatus next){
  if(next==status)return;
  if(!status.canMoveTo(next))throw new IllegalStateException("seat "+id+": "+status+" -> "+next+" is not allowed");
  status=next;
 }
}
final class SeatGateway{
 private final CsvTable courses,seats,queue;
 SeatGateway(Path dir){
  courses=new CsvTable(dir.resolve("b_courses.csv"));
  seats=new CsvTable(dir.resolve("b_seats.csv"));
  queue=new CsvTable(dir.resolve("b_waitlist.csv"));
  courses.seedIfEmpty("CS101,30","CS201,3","CS301,0","MATH101,30","MATH201,30");
 }
 Optional<Integer> capacity(String courseId){return courses.find(courseId).map(r->Integer.parseInt(r[1]));}
 void setCapacity(String courseId,int capacity){
  if(courses.find(courseId).isEmpty())throw new NoSuchElementException("unknown course "+courseId);
  courses.upsert(courseId,String.valueOf(capacity));
 }
 long taken(String courseId){return seats.rows().stream().filter(r->r[3].equals(courseId)&&!r[4].equals("RELEASED")).count();}
 boolean hasLiveSeat(String studentId,String courseId){return seats.rows().stream().anyMatch(r->r[2].equals(studentId)&&r[3].equals(courseId)&&!r[4].equals("RELEASED"));}
 List<String> waitlist(String courseId){return queue.rows().stream().filter(r->r[0].equals(courseId)).map(r->r[1]).collect(Collectors.toList());}
 void enqueue(String courseId,String studentId){queue.append(courseId,studentId);}
 String nextId(){return "B"+(seats.rows().size()+1);}
 void save(SeatReservation s){seats.upsert(s.id(),s.reference(),s.studentId(),s.courseId(),s.status().name());}
 Optional<SeatReservation> load(String id){return seats.find(id).map(SeatGateway::seat);}
 Optional<SeatReservation> byReference(String reference){return seats.rows().stream().filter(r->r[1].equals(reference)).findFirst().map(SeatGateway::seat);}
 List<String> describe(){
  List<String> out=new ArrayList<>();
  courses.rows().forEach(r->out.add("course "+r[0]+" capacity="+r[1]));
  seats.rows().forEach(r->out.add("seat "+r[0]+" ref="+r[1]+" "+r[2]+"->"+r[3]+" "+r[4]));
  queue.rows().forEach(r->out.add("waitlist "+r[0]+" "+r[1]));
  return out;
 }
 private static SeatReservation seat(String[] r){return new SeatReservation(r[0],r[1],r[2],r[3],SeatStatus.valueOf(r[4]));}
}
