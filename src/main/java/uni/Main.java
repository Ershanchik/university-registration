package uni;
import uni.billing.BillingDesk;
import uni.eligibility.EligibilityDesk;
import uni.facade.*;
import uni.seating.SeatingDesk;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
public final class Main{
 record Wiring(RegistrationFacade facade,RegistrarAdminFacade admin){}
 private static final String MENU="""

  1 register student on course     5 demo: success (S1 -> MATH201)
  2 inspect persisted state        6 manually compensate a chain
  3 demo: B fails after A          7 change data (capacity, credit limit, balance)
  4 demo: C fails after A and B    8 reset persisted state
  0 exit
 > """;
 static Wiring build(Path dir){
  EligibilityDesk a=new EligibilityDesk(dir);
  SeatingDesk b=new SeatingDesk(dir);
  BillingDesk c=new BillingDesk(dir);
  ChainLedger ledger=new ChainLedger(dir);
  return new Wiring(new RegistrationFacade(a,b,c,ledger),new RegistrarAdminFacade(a,b,c,ledger));
 }
 static void reset(Path dir)throws IOException{
  if(!Files.isDirectory(dir))return;
  List<Path> files;
  try(Stream<Path> s=Files.list(dir)){files=s.filter(f->f.toString().endsWith(".csv")).collect(Collectors.toList());}
  for(Path f:files)Files.delete(f);
 }
 static String show(ChainLedger.Chain c){return c.id()+" "+c.studentId()+"->"+c.courseId()+" "+c.state()+" ("+c.detail()+")";}
 private static String ask(Scanner in,PrintStream out,String prompt){out.print(prompt);out.flush();return in.hasNextLine()?in.nextLine().trim():"";}
 private static void register(Wiring w,PrintStream out,String student,String course){
  out.println("register "+student+" -> "+course);
  try{out.println("OK: "+show(w.facade().register(student,course)));}
  catch(RegistrationException.StepFailed e){out.println("FAILED at stage "+e.stage()+": "+e.getCause().getMessage());out.println("chain: "+show(e.chain()));}
  catch(RegistrationException e){out.println("REJECTED: "+e.getMessage());}
 }
 private static void tune(Wiring w,Scanner in,PrintStream out){
  String what=ask(in,out,"1 capacity, 2 credit limit, 3 balance: ");
  String id=ask(in,out,what.equals("1")?"course id: ":"student id: ");
  int value=Integer.parseInt(ask(in,out,"new value: "));
  switch(what){
   case "1"->w.admin().setCapacity(id,value);
   case "2"->w.admin().setCreditLimit(id,value);
   case "3"->w.admin().setBalance(id,value);
   default->throw new IllegalArgumentException("choose 1, 2 or 3");
  }
  out.println("updated");
 }
 static void run(Scanner in,PrintStream out,Path dir)throws IOException{
  Wiring w=build(dir);
  w.facade().recover().forEach(c->out.println("recovered after restart: "+show(c)));
  while(true){
   out.print(MENU);
   out.flush();
   if(!in.hasNextLine())return;
   String choice=in.nextLine().trim();
   try{
    switch(choice){
     case "1"->register(w,out,ask(in,out,"student id: "),ask(in,out,"course id: "));
     case "2"->w.admin().snapshot().forEach(out::println);
     case "3"->register(w,out,"S1","CS301");
     case "4"->register(w,out,"S2","CS201");
     case "5"->register(w,out,"S1","MATH201");
     case "6"->out.println(show(w.admin().compensate(ask(in,out,"chain id: "))));
     case "7"->tune(w,in,out);
     case "8"->{reset(dir);w=build(dir);out.println("persisted state reset");}
     case "0"->{return;}
     default->out.println("unknown option");
    }
   }catch(RuntimeException e){out.println("error: "+e.getMessage());}
  }
 }
 public static void main(String[] args)throws IOException{
  Path dir=Path.of(args.length>0?args[0]:"data");
  if(args.length>1&&args[1].equals("--reset"))reset(dir);
  run(new Scanner(System.in),System.out,dir);
 }
}
