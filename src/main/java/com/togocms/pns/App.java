package com.togocms.pns;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.springframework.core.io.Resource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import com.clickntap.tool.types.Datetime;
import com.togocms.pns.api.AndroidNotification;
import com.togocms.pns.api.IOSNotification;
import com.togocms.pns.api.PushNotification;
import com.togocms.pns.api.PushNotificationService;
import com.togocms.pns.bo.Channel;
import com.togocms.pns.bo.Device;
import com.togocms.pns.bo.Message;
import com.togocms.pns.bo.Push;

public class App extends com.clickntap.hub.App implements PushNotificationService {

	private Resource resourcesDir;

	public Resource getResourcesDir() {
		return resourcesDir;
	}

	public void setResourcesDir(Resource resourcesDir) {
		this.resourcesDir = resourcesDir;
	}

	public List<Channel> getChannels() throws Exception {
		return getBOListByClass(Channel.class, "all");
	}

	public Long sendBroadcastNotification(String apiKey, PushNotification notification) throws Exception {
		Long id = createMessage(apiKey, notification);
		return id;
	}

	public Long sendNotification(String apiKey, PushNotification notification, Long userId) throws Exception {
		Long id = createMessage(apiKey, notification);
		return id;
	}

	public Long sendGroupNotification(String apiKey, PushNotification notification, List<Long> userIds) throws Exception {
		Long id = createMessage(apiKey, notification);
		return id;
	}

	public void init() throws Exception {
		exportKeyStores();
	}

	public void exportKeyStores() throws Exception {
		for (Channel channel : getChannels()) {
			if (channel.getKeyStore() != null) {
				File f = geyKeyStorePath(channel);
				FileUtils.writeByteArrayToFile(f, Base64.decodeBase64(channel.getKeyStore()));
			}
		}
	}

	private File geyKeyStorePath(Channel channel) throws IOException {
		return new File(getResourcesDir().getFile().getCanonicalPath() + "/" + channel.getId() + "_" + channel.getProduction() + ".p12");
	}

	public void sync() throws Exception {
		executeTransaction(new TransactionCallback() {
			public Object doInTransaction(TransactionStatus status) {
				try {
					Message message = new Message();
					message.setApp(App.this);
					message.read("next");
					if (message.getId() != null) {
						message.read();
						if (message.getWorkflow().intValue() == 0) {
							Channel channel = message.getChannel();
							for (Device device : channel.getDevices()) {
								Push item = new Push();
								item.setApp(App.this);
								item.setToken(device.getToken());
								item.setPlatform(device.getPlatform());
								item.setMessageId(message.getId());
								item.setCreationTime(new Datetime());
								item.setLastModified(item.getCreationTime());
								item.create();
							}
							message.setLastModified(new Datetime());
							message.execute("queued");
						} else if (message.getWorkflow().intValue() == 1) {
							message.setAndroidSent(0);
							message.setAndroidFails(0);
							message.setIosSent(0);
							message.setIosFails(0);
							{ // android
								List<Push> androidDevices = message.getNextAndroidPushes();
								while (androidDevices.size() != 0) {
									AndroidNotification notification = new AndroidNotification();
									notification.setSecretKey(message.getChannel().getSecretKey());
									notification.setTitle(message.getTitle());
									notification.setAlert(message.getAlert());
									for (Push push : androidDevices) {
										notification.addToken(push.getToken());
										push.delete();
									}
									status.createSavepoint();
									int androidSent = androidDevices.size();
									int androidFails = 0;
									for (String badToken : notification.send()) {
										Device device = new Device();
										device.setApp(App.this);
										device.setToken(badToken);
										device.read("token");
										device.execute("disable");
										androidSent--;
										androidFails++;
									}
									message.setAndroidSent(message.getAndroidSent().intValue() + androidSent);
									message.setAndroidFails(message.getAndroidFails().intValue() + androidFails);
									androidDevices = message.getNextAndroidPushes();
								}
							}
							{ // apple
								List<Push> iosDevices = message.getNextIosPushes();
								while (iosDevices.size() != 0) {
									IOSNotification notification = new IOSNotification();
									notification.setKeyStorePath(geyKeyStorePath(message.getChannel()).getCanonicalPath());
									notification.setKeyStorePassword(message.getChannel().getKeyStorePassword());
									notification.setAlert(message.getAlert());
									for (Push push : iosDevices) {
										notification.addToken(push.getToken());
										push.delete();
									}
									status.createSavepoint();
									int iosSent = iosDevices.size();
									int iosFails = 0;
									for (String badToken : notification.send()) {
										Device device = new Device();
										device.setApp(App.this);
										device.setToken(badToken);
										device.read("token");
										device.execute("disable");
										iosSent--;
										iosFails++;
									}
									message.setIosSent(message.getIosSent().intValue() + iosSent);
									message.setIosFails(message.getIosFails().intValue() + iosFails);
									iosDevices = message.getNextIosPushes();
								}
							}
							message.setLastModified(new Datetime());
							message.execute("sent");
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					status.setRollbackOnly();
				}
				return null;
			}
		});
	}

	private Number findChannelId(String apiKey) throws Exception {
		Channel channel = new Channel();
		channel.setApp(this);
		channel.setApiKey(apiKey);
		channel.read("apiKey");
		return channel.getId();
	}

	private Long createMessage(String apiKey, PushNotification notification) throws Exception {
		Message message = new Message();
		message.setApp(this);
		message.setTitle(notification.getTitle());
		message.setAlert(notification.getAlert());
		message.setChannelId(findChannelId(apiKey));
		message.setCreationTime(new Datetime());
		message.setLastModified(message.getCreationTime());
		message.setWorkflow(0);
		message.create();
		return message.getId().longValue();
	}

}
