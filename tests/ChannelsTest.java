import com.live.tv.Channels;
public class ChannelsTest {
    static void check(boolean ok) { if(!ok)throw new AssertionError(); }
    public static void main(String[] args) {
        check(Channels.IDS.length==18);
        check(Channels.wrap(-1)==17); check(Channels.wrap(18)==0);
        check(Channels.wrap(-18)==0); check(Channels.wrap(19)==1);
        check(Channels.playerId(9).equals("cctvjilu9"));
        check(Channels.playerId(14).equals("cctvchild14"));
        check(Channels.playerId(13).equals("cctv13"));
        check(Channels.playerId(-1).equals("cctv17"));
        check(Channels.NAMES.length==Channels.IDS.length);
        for(int i=0;i<Channels.IDS.length;i++)check(Channels.allowed(Channels.url(i)));
        check(Channels.url(9).contains("cctvjilu"));check(Channels.url(14).contains("cctvchild"));
        check(Channels.url(-1).equals("https://tv.cctv.com/live/cctv17/"));
        check(Channels.NAMES[3].equals("CCTV-4 中文国际（亚）"));
        for(String removed:new String[]{"cctveurope","cctvamerica"}) {
            for(String suffix:new String[]{"/","/m/","/index.shtml"})
                check(!Channels.allowed("https://tv.cctv.com/live/"+removed+suffix));
        }
        for(String s:new String[]{"http://tv.cctv.com/live/cctv13/","https://tv.cctv.com.evil.test/live/cctv13/","https://evil.test/live/cctv13/","file:///etc/passwd","javascript:alert(1)","https://tv.cctv.com:444/live/cctv13/","https://user@tv.cctv.com/live/cctv13/","https://tv.cctv.com/live/../account/","https://tv.cctv.com/"})check(!Channels.allowed(s));
        System.out.println("Channel navigation and URL boundary tests passed.");
    }
}
