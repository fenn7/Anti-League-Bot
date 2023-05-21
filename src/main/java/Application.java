import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import kotlin.Pair;
import kotlin.Triple;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.user.UserActivityEndEvent;
import net.dv8tion.jda.api.events.user.UserActivityStartEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateActivitiesEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public class Application extends ListenerAdapter {
    public static final List<String> LEAGUE_REACTION_EMOJIS = List.of("🤮", "🤓", "🤢");
    public static final String BRUH_FUNNY_0 = "https://media.discordapp.net/attachments/1089873652008362014/1089873701140439070/bruhfunny0.gif?width=622&height=622";
    public static final String BRUH_FUNNY_1 = "https://media.discordapp.net/attachments/1089873652008362014/1089875840642326538/bruhfunny1.gif?width=622&height=622";
    public static final String SHALL_NOT_BE_NAMED = "League of Legends";
    public static final String KEYWORD = "judgment";
    public static final String SET_ALARM = "set_alarm";
    public static final String SET_CHANNEL = "set_channel";
    public static final String SET_GAME = "set_game";

    public static final Logger LOGGER = Logger.getLogger("Logger");

    private static final DB DATABASE = DBMaker.fileDB("guilty_sinners.mapdb").transactionEnable().make();
    private static final Map<Long, Long> judgmentTracker = DATABASE.hashMap("judgmentTracker")
        .keySerializer(Serializer.LONG)
        .valueSerializer(Serializer.LONG)
        .createOrOpen();
    private static final Map<Long, Long> currentTimeTracker = DATABASE.hashMap("currentTracker")
        .keySerializer(Serializer.LONG)
        .valueSerializer(Serializer.LONG)
        .createOrOpen();
    private static final Map<Long, String> currentGameTracker = DATABASE.hashMap("currentGameTracker")
        .keySerializer(Serializer.LONG)
        .valueSerializer(Serializer.STRING)
        .createOrOpen();
    private static final Map<Long, Boolean> alarmPermissions = DATABASE.hashMap("alarmPermissions")
        .keySerializer(Serializer.LONG)
        .valueSerializer(Serializer.BOOLEAN)
        .createOrOpen();
    private static final Map<Long, Long> alarmChannels = DATABASE.hashMap("alarmChannels")
        .keySerializer(Serializer.LONG)
        .valueSerializer(Serializer.LONG)
        .createOrOpen();

    public static void main(String[] args) {
        JDABuilder.createDefault(args[0])
            .addEventListeners(new Application())
            .enableIntents(
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS,
                GatewayIntent.GUILD_PRESENCES
            ).enableCache(
                CacheFlag.ACTIVITY
            ).build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        var message = event.getMessage().getContentRaw();
        if (message.toLowerCase().contains("league of legends")) {
            for (String emojiCode : LEAGUE_REACTION_EMOJIS) {
                event.getChannel().addReactionById(event.getMessageId(), Emoji.fromUnicode(emojiCode)).queue();
            }
            event.getMessage().reply(BRUH_FUNNY_0).queue();
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getName().equals(KEYWORD)) {
            OptionMapping username = event.getOption("user");
            if (username == null) {
                event.reply("Must specify a user: '/" + KEYWORD + " [user]'.").queue();
            } else {
                judgeUser(username.getAsUser(), event);
            }
        }
        else if (event.getName().equals(SET_CHANNEL)) {
            var guildID = event.getGuild().getIdLong();
            OptionMapping channel = event.getOption("channel");
            var channelID = channel == null ? event.getChannel().getIdLong() : channel.getAsChannel().getIdLong();
            this.addEntryToTracker(alarmChannels, new Pair<>(guildID, channelID));
            event.reply("Anti-League Alarm will now warn in channel: " + event.getGuild().getTextChannelById(channelID).getName()).queue();
        }
        else if (event.getName().equals(SET_ALARM)) {
            var guildID = event.getGuild().getIdLong();
            OptionMapping channel = event.getOption("switch");
            var currentPermission = channel.getAsBoolean();
            this.addEntryToTracker(alarmPermissions, new Pair<>(guildID, currentPermission));
            event.reply("Anti-League Alarm is now " + (currentPermission ? "" : "DIS") + "ARMED").queue();
        }
        else if (event.getName().equals(SET_GAME)) {
            var guildID = event.getGuild().getIdLong();
            OptionMapping game = event.getOption("game");
            var gameName = game.getAsString();
            this.addEntryToTracker(currentGameTracker, new Pair<>(guildID, gameName));
            event.reply("Anti-League Alarm is now tracking: " + gameName).queue();
        }
    }

    @Override
    public void onGuildReady(@NotNull GuildReadyEvent event) {
        var userOption = new OptionData(OptionType.USER, "user", "Pass judgment upon this user", true);
        var channelOption = new OptionData(OptionType.CHANNEL, "channel", "Alarm will warn in this channel", true);
        var switchOption = new OptionData(OptionType.BOOLEAN, "switch", "Turn the alarm on or off", true);
        var gameOption = new OptionData(OptionType.STRING, "game", "Set the game to be tracked and judged", true);

        var judgmentCommand = Commands.slash(KEYWORD, "Judge a user's League habits").addOptions(userOption);
        var setChannelCommand = Commands.slash(SET_CHANNEL, "Set the channel for the alarm to warn in").addOptions(channelOption);
        var alarmCommand = Commands.slash(SET_ALARM, "Toggle an alarm that warns when a user starts playing League").addOptions(switchOption);
        var gameCommand = Commands.slash(SET_GAME, "Set the game to be tracked and judged").addOptions(gameOption);
        event.getGuild().updateCommands().addCommands(judgmentCommand, alarmCommand, setChannelCommand, gameCommand).queue();
    }

    private void judgeUser(User user, SlashCommandInteractionEvent event) {
        var time = judgmentTracker.getOrDefault(user.getIdLong(), 0L) + currentTimeTracker.getOrDefault(user.getIdLong(), 0L);
        if (time == 0L) {
            event.reply(user.getName() + " has not played " + SHALL_NOT_BE_NAMED + " to my knowledge. Good on them!").queue();
        } else {
            var isCurrentlyPlaying = currentTimeTracker.containsKey(user.getIdLong());
            var convertedTime = getHoursMinutesSeconds(isCurrentlyPlaying
                ? time + System.currentTimeMillis() / 1000 - currentTimeTracker.getOrDefault(user.getIdLong(), 0L)
                : time
            );
            event.reply(user.getName() + " has played " + SHALL_NOT_BE_NAMED + " for "
                + convertedTime.getFirst() + " hours, "
                + convertedTime.getSecond() + " minutes, "
                + convertedTime.getThird() + " seconds"
                + (isCurrentlyPlaying ? ", and is currently playing L**gue!" : ".")
            ).queue();
            if (isCurrentlyPlaying) {
                 event.getChannel().sendMessage(BRUH_FUNNY_1).queue();
            }
        }
    }

    private Triple<Integer, Integer, Integer> getHoursMinutesSeconds(double time) {
        var hours = (int) (time / 3600);
        var minutes = (int) ((time - hours * 3600) / 60);
        var seconds = (int) (time - hours * 3600 - minutes * 60);
        return new Triple<>(hours, minutes, seconds);
    }

    private <T> void addEntryToTracker(Map<Long, T> tracker, Pair<Long, T> entry) {
        tracker.put(entry.getFirst(), entry.getSecond());
        DATABASE.commit();
    }

    private <T> T removeEntryFromTracker(Map<Long, T> tracker, Long entryKey) {
        var entryValue = tracker.remove(entryKey);
        DATABASE.commit();
        return entryValue;
    }

    @Override
    public void onUserActivityStart(UserActivityStartEvent event) {
        var user = event.getUser();
        if (user.isBot()) {
            return;
        }
        LOGGER.info(event.getNewActivity().getName());
        if (event.getNewActivity().getName().equals(SHALL_NOT_BE_NAMED) && !currentTimeTracker.containsKey(user.getIdLong())) {
            this.addEntryToTracker(currentTimeTracker, new Pair<>(user.getIdLong(), System.currentTimeMillis() / 1000));
            if (alarmPermissions.get(event.getGuild().getIdLong())) {
                var channelID = alarmChannels.get(event.getGuild().getIdLong());
                event.getGuild().getTextChannelById(channelID).sendMessage(user.getName() + " has started playing L**gue!").queue();
                event.getGuild().getTextChannelById(channelID).sendMessage(BRUH_FUNNY_1).queue();
            }
        }
    }

    @Override
    public void onUserActivityEnd(UserActivityEndEvent event) {
        var user = event.getUser();
        if (user.isBot()) {
            return;
        }
        LOGGER.info(event.getOldActivity().getName());
        if (event.getOldActivity().getName().equals(SHALL_NOT_BE_NAMED) && currentTimeTracker.containsKey(user.getIdLong())) {
            var lastTime = this.removeEntryFromTracker(currentTimeTracker, user.getIdLong());
            this.addEntryToTracker(judgmentTracker, new Pair<>(user.getIdLong(),
                judgmentTracker.getOrDefault(user.getIdLong(), 0L) + (System.currentTimeMillis() / 1000) - lastTime));
        }
    }

    @Override
    public void onUserUpdateActivities(@NotNull UserUpdateActivitiesEvent event) {
        LOGGER.info(event.getNewValue().toString());
        LOGGER.info(event.getOldValue().toString());
        super.onUserUpdateActivities(event);
    }
}