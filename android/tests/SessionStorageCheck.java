package com.pawlink.capture;
import java.io.*;
import java.nio.file.*;
public class SessionStorageCheck {
    interface Op {void run() throws Exception;}
    static void rejects(Op op)throws Exception {try{op.run();throw new AssertionError("Unsafe deletion allowed");}catch(IOException expected){}}
    public static void main(String[] a)throws Exception {
        Path temp=Files.createTempDirectory("paw-delete-test-");File root=Files.createDirectory(temp.resolve("sessions")).toFile();
        Path outside=Files.writeString(temp.resolve("keep.txt"),"keep");
        File session=Files.createDirectory(root.toPath().resolve("fixture")).toFile();
        Files.createDirectories(session.toPath().resolve("reviews/revision-1"));Files.writeString(session.toPath().resolve("reviews/revision-1/review.json"),"{}");Files.writeString(session.toPath().resolve("video.mp4"),"test");
        rejects(()->SessionStorage.deleteSession(root,root,null));rejects(()->SessionStorage.deleteSession(root,temp.toFile(),null));rejects(()->SessionStorage.deleteSession(root,session,session));
        if(!session.exists())throw new AssertionError("Active session removed");
        Path link=root.toPath().resolve("external-link");Files.createSymbolicLink(link,temp);rejects(()->SessionStorage.deleteSession(root,link.toFile(),null));
        Files.createSymbolicLink(session.toPath().resolve("nested-link"),outside);
        SessionStorage.deleteSession(root,session,null);
        if(session.exists()||!Files.exists(outside)||!root.exists())throw new AssertionError("Deletion bounds violated");
        SessionStorage.deleteSession(root,session,null);
        Files.delete(link);Files.delete(outside);Files.delete(root.toPath());Files.delete(temp);
        System.out.println("PASS: recursive deletion, active-session guard, root/traversal guard, symlink isolation, repeat deletion.");
    }
}
