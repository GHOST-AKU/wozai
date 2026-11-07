package org.nearbymesh.radio;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Three real Core processes behind pipe-backed CoC streams: adapter integration, NOT RF/isolation proof. */
public final class CocCoreIntegrationTest {
    static final class Node implements AutoCloseable {
        final Process process;final BufferedWriter commands;final Path state;
        final List<String> lines=new CopyOnWriteArrayList<>();
        Node(String binary,boolean relay)throws Exception {
            state=Files.createTempDirectory("mesh-coc-core-");
            List<String> args=new ArrayList<>(Arrays.asList(binary,"run","--state",state.toString(),"--listen","127.0.0.1:0"));
            if(relay)args.add("--relay");process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            commands=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),"UTF-8"));
            Thread reader=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream(),"UTF-8"))){
                String line;while((line=in.readLine())!=null){if(lines.size()>=512)throw new AssertionError("Core output bound");lines.add(line);}
            }catch(IOException ignored){}});reader.setDaemon(true);reader.start();
            waitLine("\"event\":\"ready\"",5000);
        }
        int port()throws Exception{
            String ready=waitLine("\"event\":\"ready\"",5000);
            Matcher m=Pattern.compile("127\\.0\\.0\\.1:(\\d+)").matcher(ready);
            CocStreamsTest.check(m.find(),"ready address");return Integer.parseInt(m.group(1));
        }
        String waitLine(String part,long millis)throws Exception{
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            while(System.nanoTime()<end){for(String line:lines)if(line.contains(part))return line;Thread.sleep(10);}
            return null;
        }
        synchronized void cmd(String text)throws IOException{commands.write(text);commands.newLine();commands.flush();}
        void send(String text)throws IOException{cmd("{\"cmd\":\"send\",\"text\":\""+text+"\"}");}
        long count(String part){return lines.stream().filter(s->s.contains(part)).count();}
        String received(String body,long timeout)throws Exception{return waitLine("\"body\":\""+body+"\"",timeout);}
        public void close()throws Exception{
            try{cmd("{\"cmd\":\"quit\"}");}catch(IOException ignored){}
            if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}
            commands.close();try(java.util.stream.Stream<Path> paths=Files.walk(state)){
                paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});
            }
        }
    }
    static CocStreamsTest.Pipe[] pair()throws Exception{
        PipedInputStream a=new PipedInputStream(65536),b=new PipedInputStream(65536);
        return new CocStreamsTest.Pipe[]{new CocStreamsTest.Pipe(a,new PipedOutputStream(b)),new CocStreamsTest.Pipe(b,new PipedOutputStream(a))};
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Core binary required");
        try(Node a=new Node(args[0],false);Node b=new Node(args[0],true);Node c=new Node(args[0],false)){
            CocStreams sa=new CocStreams(RadioConfig.publicMesh("ble","A",a.port()),new CocStreamsTest.Observer());
            CocStreams sb=new CocStreams(RadioConfig.publicMesh("ble","B",b.port()),new CocStreamsTest.Observer());
            CocStreams sc=new CocStreams(RadioConfig.publicMesh("ble","C",c.port()),new CocStreamsTest.Observer());
            try{
                CocStreamsTest.Pipe[] ab=pair(),bc=pair();sb.accept(ab[1]);sa.dial("B",()->ab[0]);sc.accept(bc[1]);sb.dial("C",()->bc[0]);
                CocStreamsTest.waitFor(()->a.count("\"event\":\"neighbor_up\"")>=1&&b.count("\"event\":\"neighbor_up\"")>=2&&c.count("\"event\":\"neighbor_up\"")>=1);
                a.send("coc-forward");CocStreamsTest.check(c.received("coc-forward",5000)!=null,"A-B-C message");
                c.send("coc-reverse");CocStreamsTest.check(a.received("coc-reverse",5000)!=null,"C-B-A message");
                b.cmd("{\"cmd\":\"relay\",\"enabled\":false}");CocStreamsTest.check(b.waitLine("\"enabled\":false",2000)!=null,"relay off acknowledged");
                a.send("coc-relay-off");CocStreamsTest.check(c.received("coc-relay-off",750)==null,"relay-off negative");
                b.cmd("{\"cmd\":\"relay\",\"enabled\":true}");CocStreamsTest.check(b.waitLine("\"enabled\":true",2000)!=null,"relay restored");
                ab[0].in.close();CocStreamsTest.waitFor(()->sa.size()==0&&sb.size()==1);
                CocStreamsTest.Pipe[] restored=pair();sb.accept(restored[1]);sa.dial("B",()->restored[0]);
                CocStreamsTest.waitFor(()->a.count("\"event\":\"neighbor_up\"")>=2);
                a.send("coc-recovered");CocStreamsTest.check(c.received("coc-recovered",5000)!=null,"partition recovery");
                CocStreamsTest.check(c.count("\"body\":\"coc-forward\"")==1,"no duplicate display after reconnect");
            }finally{sa.close();sb.close();sc.close();}
        }
        System.out.println("CoC/Core integration: three OS processes, bidirectional relay, relay-off, reconnect, dedupe PASS (pipe model only)");
    }
}
