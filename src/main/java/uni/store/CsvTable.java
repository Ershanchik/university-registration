package uni.store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
public final class CsvTable{
 private final Path file;
 public CsvTable(Path file){
  this.file=file;
  try{Files.createDirectories(file.toAbsolutePath().getParent());if(Files.notExists(file))Files.createFile(file);}catch(IOException e){throw new UncheckedIOException(e);}
 }
 public synchronized List<String[]> rows(){
  try{return Files.readAllLines(file).stream().filter(l->!l.isBlank()).map(l->l.split(",",-1)).collect(Collectors.toCollection(ArrayList::new));}catch(IOException e){throw new UncheckedIOException(e);}
 }
 public synchronized void write(List<String[]> rows){
  try{
   Path tmp=file.resolveSibling(file.getFileName()+".tmp");
   Files.write(tmp,rows.stream().map(CsvTable::line).collect(Collectors.toList()));
   Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(IOException e){throw new UncheckedIOException(e);}
 }
 public synchronized void append(String... row){List<String[]> all=rows();all.add(row);write(all);}
 public synchronized Optional<String[]> find(String key){return rows().stream().filter(r->r[0].equals(key)).findFirst();}
 public synchronized void upsert(String... row){
  List<String[]> all=rows();
  boolean found=false;
  for(int i=0;i<all.size();i++)if(all.get(i)[0].equals(row[0])){all.set(i,row);found=true;}
  if(!found)all.add(row);
  write(all);
 }
 public synchronized void seedIfEmpty(String... lines){if(rows().isEmpty())write(Arrays.stream(lines).map(l->l.split(",",-1)).collect(Collectors.toList()));}
 private static String line(String[] row){return Arrays.stream(row).map(v->v.replace(',',';').replace('\n',' ').replace('\r',' ')).collect(Collectors.joining(","));}
}
