package net.runelite.client.plugins.chatspam;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.*;
import java.net.Socket;
import java.util.List;

import static net.runelite.client.util.Text.sanitize;

@PluginDescriptor(
        name = "Chat Spam",
        description = "Collects messages for a spam filter",
        tags = {"chat", "spam"}
)
@Slf4j
public class ChatSpamPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ChatSpamConfig config;

    private PrintWriter chatlog = null;
    private Socket clientSocket = null;
    private PrintWriter clientOut = null;
    private BufferedReader clientIn = null;
    private LRUCache<String, Boolean> chatCache = new LRUCache<String, Boolean>(100);

    @Provides
    ChatSpamConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ChatSpamConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        chatlog = new PrintWriter(new FileOutputStream("chat.log", true));
        clientSocket = new Socket("127.0.0.1", 6978);
        clientOut = new PrintWriter(clientSocket.getOutputStream(), true);
        clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
    }

    @Override
    protected void shutDown() throws Exception
    {
        if (chatlog != null) {
            chatlog.close();
            chatlog = null;
        }

        if (clientOut != null) {
            clientOut.close();
            clientOut = null;
        }

        if (clientIn != null) {
            clientIn.close();
            clientIn = null;
        }

        if (clientSocket != null) {
            clientSocket.close();
            clientSocket = null;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case HOPPING:
                if (chatlog != null) {
                    chatlog.close();
                }

                chatlog = null;
                break;
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        MessageNode messageNode = chatMessage.getMessageNode();

        if (chatlog == null) {
            try {
                chatlog = new PrintWriter(new FileOutputStream("chat.log", true));
            } catch (FileNotFoundException e) {
                log.error("Could not open chat log");
            }
        }

        if (chatMessage.getType() == ChatMessageType.PUBLICCHAT) {
            clientThread.invokeLater(() -> {
                chatlog.println(messageNode.getValue());
            });
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        if (!"chatFilterCheck".equals(event.getEventName()))
        {
            return;
        }

        int[] intStack = client.getIntStack();
        int intStackSize = client.getIntStackSize();
        ChatMessageType chatMessageType = ChatMessageType.of(intStack[intStackSize - 1]);

        // Only filter public chat and private messages
        switch (chatMessageType)
        {
            case PUBLICCHAT:
            case MODCHAT:
            case AUTOTYPER:
            case PRIVATECHAT:
            case MODPRIVATECHAT:
            case FRIENDSCHAT:
                break;
            default:
                return;
        }

        String[] stringStack = client.getStringStack();
        int stringStackSize = client.getStringStackSize();

        String message = stringStack[stringStackSize - 1];

        if (isSpam(message))
        {
            // Block the message
            intStack[intStackSize - 2] = 0;
        }
        else
        {
            // Replace the message
            stringStack[stringStackSize - 1] = message;
        }

    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event)
    {
        if (!(event.getActor() instanceof Player))
        {
            return;
        }

        if (isSpam(event.getOverheadText()))
        {
            event.getActor().setOverheadText(" ");
        }
        else {
            event.getActor().setOverheadText(event.getActor().getOverheadText());
        }

    }

    private boolean isSpam(String input) {
        if (clientSocket == null) {
            try {
                clientSocket = new Socket("127.0.0.1", 6978);
            } catch (IOException e) {
                log.error("Could not create socket connection to spam db");
            }
        }

        if (clientOut == null) {
            try {
                clientOut = new PrintWriter(clientSocket.getOutputStream(), true);
            } catch (IOException e) {
                log.error("Could not create socket connection to spam db");
            }
        }

        if (clientIn == null) {
            try {
                clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            } catch (IOException e) {
                log.error("Could not create socket connection to spam db");
            }
        }

        String msg = sanitize(input);

        if (chatCache.containsKey(msg)) {
            return chatCache.get(msg);
        }

        clientOut.println(msg);

        String resp = null;
        try {
            resp = clientIn.readLine();
        } catch (IOException e) {
            log.error("Could not read from socket");
        }

        if (resp != null && resp.equalsIgnoreCase("SPAM")) {
            chatCache.put(msg, true);
            return true;
        }

        chatCache.put(msg, false);
        return false;
    }

    private void markSpam(String input) {
        log.info("Got to markSpam: " + input);
    }
}
