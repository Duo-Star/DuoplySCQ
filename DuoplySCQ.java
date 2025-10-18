package com.duo.mc.scq;

//net.fabricmc.api
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

//net.minecraft
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

//网络相关
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

//com.google
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

//Logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//java
import java.io.*;
import java.net.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class DuoplySCQ implements ModInitializer {
	public static final String MOD_ID = "duoply-scq";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// WebSocket客户端
	private static WebSocketClient webSocketClient;
	// WebSocket服务器地址
	private static final String WS_SERVER = "ws://哈基米";
	// WebSocket token认证
	private static final String WS_TOKEN = "如来！"; // token
	// 重连间隔（毫秒）
	private static final int RECONNECT_INTERVAL = 10 * 1000;
	// 是否启用WebSocket
	private static final boolean ENABLE_WEBSOCKET = true;

	private static final String API_URL = "https://api.deepseek.com/chat/completions";
	private static final String API_KEY = "sk-南北绿豆";
	private static final String MODEL = "deepseek-chat";

	public static final Boolean TestVersion = true;
	public static final String TestVersionStr = TestVersion ? "-----### 测试版 ###-----" : "";
	public static final String helloMsg ="""
    Minecraft服务器连接成功
    java 1.21.6 fabric [Duoply] from MathForest
    scq(Server connect QQ)版本: 1.0.9
    """+TestVersionStr+"""
    
    更新日志：
    1.0.1(2025.9.2) 首次发布，实现消息互通
    1.0.2(..4) 实现管理员指令回传
    1.0.3(..8) 增强网络连接安全性
    1.0.4(..12) DeepSeek 加入了世界
    1.0.5(..14) 修复DS响应异常，修复消息空值
    1.0.6(..14) 增加死亡消息和成就消息
    1.0.7(..14) 提升DS智商，优先mc领域解惑
    1.0.8(..15) 日志优化，性能优化，指令变更[.op ][.ntc ]
    1.0.9(..16) 不再显示uuid，加入「首个玩家」「狐狐小憩」等
    """;


	public static final String DSPropertyStr= """
	你在一个Minecraft服务器中为大家解答疑惑，你的回答要简短，调理清晰，响应时间不要长过10秒。
	我们的版本是java mc 1.21.6，一定要注意信息时效性。
	""";

	public static final Boolean SilenceMode = false;

	public static final Boolean WebSocketMsgInfo =false;

	public static final Boolean DebugMode = true;

	public static final Boolean TryToReconnect = true;

	// 服务器实例
	private static net.minecraft.server.MinecraftServer minecraftServer;
	// 指令前缀
	private static final String SAY_COMMAND_PREFIX = ".ntc ";

	private static final String COMMAND_PREFIX = ".op ";

	private static final String DEEPSEEK_PREFIX = "DS ";

	private static final Integer GROUP_ID_INT = 114514;// 你的群号
	private static final Integer Duo_INT = 5201314;//管理员QQ

	private static final long GROUP_ID = 114514; // 你的群号

	public static String INIT_DATE=String.valueOf(LocalDate.now().getDayOfMonth());



	// 线程池用于异步处理
	private static final ExecutorService executorService = Executors.newCachedThreadPool();

	private static void infoMsg(String msg){
		if (DebugMode) {
			LOGGER.info(msg);
		}
	}



	@Override
	public void onInitialize() {
		infoMsg("DuoplySCQ模组初始化完成!");
		// 初始化WebSocket连接
		if (ENABLE_WEBSOCKET) {
			initWebSocket();
		}
		// 监听玩家加入游戏
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			String message = "";
			if (INIT_DATE.equals(String.valueOf(LocalDate.now().getDayOfMonth()))){
				message = String.format("[玩家加入]%s", player.getName().getString());
				if (server.getCurrentPlayerCount()==3){
					message=message+"\n三人行必有我师";
				}else{
					if (server.getCurrentPlayerCount()==5){
						message=message+"\n五神归位";
					}
				}
			}else {
				message = String.format("[玩家加入]%s\n☀\uFE0F 你是今天首个玩家哦 awa", player.getName().getString());
				INIT_DATE = String.valueOf(LocalDate.now().getDayOfMonth());
				if (minecraftServer != null) {
					minecraftServer.execute(() -> {
						Text broadcastText = Text.literal("[@"+player.getName().getString()+" 偷偷告诉你]你是今天首个玩家哦 awa");
						minecraftServer.getPlayerManager().broadcast(broadcastText, false);
					});
				}
			}
			infoMsg(message);
			sendGroupMessage(message);
		});

		// 监听玩家退出游戏
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayerEntity player = handler.player;
			//String message = String.format("[玩家退出]%s (UUID: %s)", player.getName().getString(), player.getUuid());
			String message = "";
			if (server.getCurrentPlayerCount()==1){
				message = String.format("[玩家退出]%s \n狐狐小憩一下", player.getName().getString());
			}else{
				message = String.format("[玩家退出]%s", player.getName().getString());
			}
			infoMsg(message);
			sendGroupMessage(message);
		});


		// 玩家死亡提醒
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayerEntity) {
				ServerPlayerEntity player = (ServerPlayerEntity) entity;
				String deathMessage = damageSource.getDeathMessage(player).getString();
				String message = String.format("[玩家归西]%s 因为 「%s」！",
						player.getName().getString(), deathMessage);
				infoMsg(message);
				sendGroupMessage(message);
			}
		});




		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			String content = message.getString();
			if (content.contains("has made the advancement") ||
					content.contains("has completed the challenge") ||
					content.contains("取得了进度") ||
					content.contains("了成就") ||
					content.contains("了目标")) {
				String achievementMessage = String.format("[玩家成就]%s ", content);
				//System.out.println(achievementMessage);
				infoMsg(achievementMessage);
				sendGroupMessage(achievementMessage);
				// sendGroupMessage(achievementMessage);
			}
			//return true;
		});




		// 监听聊天消息
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String playerName = sender.getName().getString();
			String content = message.getContent().getString();

			// 网络测试命令
			if (content.equals(".TEST-DATE")) {
				if (playerName.equals("Duo_lvmc") || playerName.equals("Duo_48395270") || true) {
					if (minecraftServer != null) {
						minecraftServer.execute(() -> {
							LocalDate today = LocalDate.now();
							System.out.println("今天的日期是: " + today);
							Text broadcastText = Text.literal("今天的日期是: " + today);
							minecraftServer.getPlayerManager().broadcast(broadcastText, false);
						});
					}
				}
				return;
			}


			if (content.startsWith(DEEPSEEK_PREFIX)) {
				String RequestContent = content.substring(DEEPSEEK_PREFIX.length()).trim();
				if (!RequestContent.isEmpty()) {
					infoMsg("[" + playerName + "请求DS] " + RequestContent);
					sendGroupMessage("[" + playerName + "请求DS] " + RequestContent);
					//新建客户端
					DeepSeekClient deepSeekClient = new DeepSeekClient();

					deepSeekClient.sendRequest(RequestContent, new DeepSeekCallback() {
						@Override
						public void onSuccess(String response) {
							infoMsg("DeepSeek响应成功: " + response.length() + "字符");
							if (minecraftServer != null) {
								minecraftServer.execute(() -> {
									// 截断过长的响应
									String displayResponse = response.length() > 500 ?
											response.substring(0, 500) + "..." : response;
									Text broadcastText = Text.literal("[DS回应] " + displayResponse);
									minecraftServer.getPlayerManager().broadcast(broadcastText, false);
									sendGroupMessage("[DS回应] " + displayResponse);
								});
							}
						}
						@Override
						public void onFailure(String error) {
							LOGGER.error("DeepSeek请求失败: " + error);
							sendGroupMessage("[DS error] " + error);
						}
					});
				}
			} else {
				String chatMessage = String.format("[mc消息]%s: %s", playerName, content);
				infoMsg(chatMessage);
				sendGroupMessage(chatMessage);
			}
		});

		// 监听服务器启动和停止
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			String message = "服务器正在启动";
			infoMsg(message);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String message = "服务器已启动";
			infoMsg(message);
			// 保存服务器实例
			minecraftServer = server;
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			String message = "服务器正在停止";
			infoMsg(message);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			String message = "服务器已停止";
			infoMsg(message);
			// 服务器停止时关闭WebSocket连接
			if (webSocketClient != null) {
				webSocketClient.close();
			}
			// 关闭线程池
			executorService.shutdown();
			// 清除服务器实例
			minecraftServer = null;
		});
	}

	/**
	 * 初始化WebSocket连接
	 */
	private void initWebSocket() {
		try {
			// 创建包含认证头的WebSocket客户端
			Map<String, String> headers = new HashMap<>();
			headers.put("Authorization", "Bearer " + WS_TOKEN);

			webSocketClient = new WebSocketClient(new URI(WS_SERVER), headers) {
				@Override
				public void onOpen(ServerHandshake handshakedata) {
					infoMsg("WebSocket连接已建立");
					sendGroupMessage(helloMsg);

				}

				@Override
				public void onMessage(String message) {
					try {
						JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
						if(jsonObject.get("raw_message") != null) {

							if (WebSocketMsgInfo) {
								infoMsg("收到WebSocket消息: " + message);
							}

							String rawMessage = jsonObject.get("raw_message").getAsString();
							int group_id = jsonObject.get("group_id").getAsInt();
							int user_id = jsonObject.get("sender").getAsJsonObject().get("user_id").getAsInt();
							String nickname = jsonObject.get("sender").getAsJsonObject().get("nickname").getAsString();

							infoMsg("rawMessage: " + rawMessage);

							// 检查是否以/say 开头
							if (rawMessage.startsWith(SAY_COMMAND_PREFIX) && group_id == GROUP_ID_INT) {
								// 提取广播内容
								String broadcastContent = rawMessage.substring(SAY_COMMAND_PREFIX.length()).trim();
								if (!broadcastContent.isEmpty()) {
									if (minecraftServer != null) {
										minecraftServer.execute(() -> {
											Text broadcastText = Text.literal(String.format("[qq广播 (%s)] ", nickname) + broadcastContent);
											minecraftServer.getPlayerManager().broadcast(broadcastText, false);
											infoMsg("已广播消息: " + broadcastContent);
											sendGroupMessage("[已广播] " + broadcastContent);
										});
									}
								}
							} else if (user_id == Duo_INT && group_id == GROUP_ID_INT) {
								if (rawMessage.startsWith(COMMAND_PREFIX)) {
									String command = rawMessage.substring(COMMAND_PREFIX.length()).trim();
									infoMsg("COMMAND_PREFIX: " + command);

									if (!command.isEmpty()) {
										executeCommand(command);
										sendGroupMessage("[管理员命令]: " + command);
									}
								}
							}
						}
					} catch (Exception e) {
						LOGGER.error("处理WebSocket消息时出错", e);
					}
				}

				@Override
				public void onClose(int code, String reason, boolean remote) {
					infoMsg("WebSocket连接关闭: " + reason + " (代码: " + code + ")"+ "尝试重新连接" + TryToReconnect);
					// 尝试重新连接
					if (TryToReconnect) {
						if (ENABLE_WEBSOCKET) {
							try {
								Thread.sleep(RECONNECT_INTERVAL);
								initWebSocket();
							} catch (InterruptedException e) {
								LOGGER.error("重连等待被中断", e);
								Thread.currentThread().interrupt();
							}
						}
					}

				}

				@Override
				public void onError(Exception ex) {
					LOGGER.error("WebSocket错误", ex);
				}
			};

			// 启动连接
			webSocketClient.connect();
		} catch (URISyntaxException e) {
			LOGGER.error("WebSocket服务器URI格式错误", e);
		}
	}

	/**
	 * 发送群聊消息到 WebSocket（Nap cat）
	 */
	private void sendGroupMessage(String msg) {
		if (ENABLE_WEBSOCKET && webSocketClient != null && webSocketClient.isOpen()) {
			try {
				JsonObject root = new JsonObject();
				root.addProperty("action", "send_group_msg");

				JsonObject params = new JsonObject();
				params.addProperty("group_id", GROUP_ID);
				params.addProperty("message", msg);

				root.add("params", params);
				root.addProperty("echo", "mc_chat");
				if (!SilenceMode) {
					webSocketClient.send(root.toString());
				}
			} catch (Exception e) {
				LOGGER.error("发送群聊消息失败", e);
			}
		}
	}

	/**
	 * 执行Minecraft指令
	 */
	private void executeCommand(String command) {
		if (minecraftServer != null && !command.trim().isEmpty()) {
			minecraftServer.execute(() -> {
				try {
					minecraftServer.getCommandManager().executeWithPrefix(
							minecraftServer.getCommandSource().withLevel(4),
							command
					);
				} catch (Exception e) {
					LOGGER.error("执行指令时发生错误: " + command, e);
					sendGroupMessage("执行指令错误: " + e.getMessage());
				}
			});
		}
	}

	interface DeepSeekCallback {
		void onSuccess(String response);
		void onFailure(String error);
	}

	public static class DeepSeekClient {
		private final Gson gson;

		public DeepSeekClient() {
			this.gson = new Gson();
		}

		public void sendRequest(String userMessage, DeepSeekCallback callback) {
			infoMsg("开始发送DeepSeek请求: " + userMessage.substring(0, Math.min(userMessage.length(), 50)) + "...");
			// 使用线程池异步执行
			executorService.submit(() -> {
				try {
					sendRequestWithRetry(userMessage, callback, 5);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			});
		}

		private void sendRequestWithRetry(String userMessage, DeepSeekCallback callback, int retries) throws InterruptedException {
			long startTime = System.currentTimeMillis();
			int currentAttempt = 1;
			long totalTime = 0;

			while (currentAttempt <= retries) {
				try {
					long attemptStartTime = System.currentTimeMillis();

					// 构建请求体
					JsonObject requestBody = new JsonObject();
					requestBody.addProperty("model", MODEL);
					requestBody.addProperty("stream", false);

					JsonArray messages = new JsonArray();
					JsonObject systemMessage = new JsonObject();
					systemMessage.addProperty("role", "system");
					systemMessage.addProperty("content", DSPropertyStr);
					messages.add(systemMessage);

					JsonObject userMessageObj = new JsonObject();
					userMessageObj.addProperty("role", "user");
					userMessageObj.addProperty("content", userMessage);
					messages.add(userMessageObj);

					requestBody.add("messages", messages);

					String requestBodyStr = gson.toJson(requestBody);

					// 创建HTTP连接
					URL url = new URL(API_URL);
					HttpURLConnection connection = (HttpURLConnection) url.openConnection();
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
					connection.setRequestProperty("Content-Type", "application/json");
					connection.setRequestProperty("Accept", "application/json");
					connection.setConnectTimeout(120000); // 120秒连接超时
					connection.setReadTimeout(180000);    // 180秒读取超时
					connection.setDoOutput(true);

					// 发送请求体
					try (OutputStream os = connection.getOutputStream()) {
						byte[] input = requestBodyStr.getBytes("utf-8");
						os.write(input, 0, input.length);
					}

					// 获取响应
					int responseCode = connection.getResponseCode();
					long elapsedTime = System.currentTimeMillis() - attemptStartTime;
					totalTime += elapsedTime;

					if (responseCode == 200) {
						// 读取响应
						StringBuilder response = new StringBuilder();
						try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
							String responseLine;
							while ((responseLine = br.readLine()) != null) {
								response.append(responseLine.trim());
							}
						}

						String responseBody = response.toString();
						infoMsg("DeepSeek请求成功，第" + currentAttempt + "次尝试，本次耗时: " + elapsedTime + "ms, 总耗时: " + totalTime + "ms");
						infoMsg("DeepSeek响应体长度: " + responseBody.length() + "字符");

						// 解析响应
						String content = parseResponse(responseBody);
						infoMsg("DeepSeek响应解析成功，内容长度: " + content.length() + "字符");
						callback.onSuccess(content);
						return;
					} else {
						String errorBody;
						try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "utf-8"))) {
							StringBuilder errorResponse = new StringBuilder();
							String line;
							while ((line = br.readLine()) != null) {
								errorResponse.append(line);
							}
							errorBody = errorResponse.toString();
						} catch (Exception e) {
							errorBody = "无法读取错误信息";
						}

						String errorMsg = "DeepSeek API错误: " + responseCode + " - " + errorBody;
						LOGGER.warn("DeepSeek请求第" + currentAttempt + "次尝试失败，已用时间: " + elapsedTime + "ms, 总时间: " + totalTime + "ms");

						if (responseCode >= 500 && currentAttempt < retries) {
							// 服务器错误，重试
							long delay = (long) (2000 * Math.pow(2, retries - currentAttempt));
							infoMsg("等待 " + delay + "ms 后重试...");
							Thread.sleep(delay);
							currentAttempt++;
						} else {
							LOGGER.error(errorMsg);
							callback.onFailure(errorMsg);
							return;
						}
					}
				} catch (SocketTimeoutException e) {
					long elapsedTime = System.currentTimeMillis() - startTime;
					LOGGER.warn("DeepSeek请求第" + currentAttempt + "次尝试超时，已用时间: " + elapsedTime + "ms");

					if (currentAttempt < retries) {
						long delay = (long) (2000 * Math.pow(2, retries - currentAttempt));
						infoMsg("等待 " + delay + "ms 后重试...");
						Thread.sleep(delay);
						currentAttempt++;
					} else {
						callback.onFailure("DeepSeek请求超时: " + e.getMessage());
						return;
					}
				} catch (IOException e) {
					long elapsedTime = System.currentTimeMillis() - startTime;
					LOGGER.warn("DeepSeek请求第" + currentAttempt + "次尝试失败，已用时间: " + elapsedTime + "ms, 错误: " + e.getMessage());

					if (currentAttempt < retries) {
						long delay = (long) (2000 * Math.pow(2, retries - currentAttempt));
						infoMsg("等待 " + delay + "ms 后重试...");
						Thread.sleep(delay);
						currentAttempt++;
					} else {
						callback.onFailure("DeepSeek请求失败: " + e.getMessage());
						return;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					callback.onFailure("请求被中断");
					return;
				} catch (Exception e) {
					callback.onFailure("未知错误: " + e.getMessage());
					return;
				}
			}
		}

		private String parseResponse(String responseBody) {
			JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
			JsonArray choices = responseJson.getAsJsonArray("choices");
			JsonObject firstChoice = choices.get(0).getAsJsonObject();
			JsonObject message = firstChoice.getAsJsonObject("message");
			return message.get("content").getAsString();
		}
	}
}
