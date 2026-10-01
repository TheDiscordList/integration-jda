// A minimal bot that publishes to TheDiscordList.
//
// From the repository root:
//   mvn -q package -DskipTests
//   mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
//   DISCORD_TOKEN=... TDL_TOKEN=... java -cp "target/classes:$(cat cp.txt)" examples/ExampleBot.java

import com.thediscordlist.jda.AutopostOptions;
import com.thediscordlist.jda.Autoposter;
import com.thediscordlist.jda.TheDiscordList;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;

public class ExampleBot {
    public static void main(String[] args) throws InterruptedException {
        JDA jda = JDABuilder.createLight(System.getenv("DISCORD_TOKEN")).build();

        Autoposter poster = TheDiscordList.autopost(jda, System.getenv("TDL_TOKEN"), new AutopostOptions()
                .listener(new Autoposter.Listener() {
                    @Override
                    public void onPosted(Autoposter.Kind type, int status) {
                        System.out.println("posted " + type + " (" + status + ")");
                    }

                    @Override
                    public void onError(Autoposter.Failure failure) {
                        System.err.println(failure);
                    }

                    @Override
                    public void onDisabled(Autoposter.Kind type, Integer status, String reason) {
                        System.err.println(type + " disabled: " + reason);
                    }
                }));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            poster.close();
            jda.shutdown();
        }));
        jda.awaitReady();
    }
}
