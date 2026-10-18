package uni.facade;
import java.util.regex.Pattern;
public class RegistrationException extends RuntimeException{
 public RegistrationException(String message){super(message);}
 public RegistrationException(String message,Throwable cause){super(message,cause);}
 public static final class InvalidRequest extends RegistrationException{
  private static final Pattern ID=Pattern.compile("[A-Za-z0-9_-]{1,20}");
  public InvalidRequest(String message){super(message);}
  public static void requireId(String label,String value){if(value==null||!ID.matcher(value).matches())throw new InvalidRequest(label+" must match [A-Za-z0-9_-]{1,20}, got '"+value+"'");}
 }
 public static final class StepFailed extends RegistrationException{
  private final String stage;
  private final ChainLedger.Chain chain;
  public StepFailed(String stage,Throwable cause,ChainLedger.Chain chain){super("stage "+stage+" failed: "+cause.getMessage(),cause);this.stage=stage;this.chain=chain;}
  public String stage(){return stage;}
  public ChainLedger.Chain chain(){return chain;}
 }
}
