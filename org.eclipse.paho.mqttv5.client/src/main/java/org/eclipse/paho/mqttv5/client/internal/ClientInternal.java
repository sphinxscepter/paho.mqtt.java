/*******************************************************************************
 * Copyright (c) 2019 IBM Corp.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution. 
 *
 * The Eclipse Public License is available at 
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 *   http://www.eclipse.org/org/documents/edl-v10.php.
 *   
 * Contributions:
 *   Ian Craggs - initial implementation
 */

package org.eclipse.paho.mqttv5.client.internal;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.Hashtable;

import org.eclipse.paho.mqttv5.client.DisconnectedBufferOptions;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientException;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttToken;
import org.eclipse.paho.mqttv5.client.persist.PersistedBuffer;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSecurityException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttConnAck;
import org.eclipse.paho.mqttv5.common.packet.MqttConnect;
import org.eclipse.paho.mqttv5.common.packet.MqttDataTypes;
import org.eclipse.paho.mqttv5.common.packet.MqttDisconnect;
import org.eclipse.paho.mqttv5.common.packet.MqttPersistableWireMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttPingResp;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.MqttPubAck;
import org.eclipse.paho.mqttv5.common.packet.MqttPubComp;
import org.eclipse.paho.mqttv5.common.packet.MqttPubRec;
import org.eclipse.paho.mqttv5.common.packet.MqttPubRel;
import org.eclipse.paho.mqttv5.common.packet.MqttPublish;
import org.eclipse.paho.mqttv5.common.packet.MqttReturnCode;
import org.eclipse.paho.mqttv5.common.packet.MqttSubAck;
import org.eclipse.paho.mqttv5.common.packet.MqttSubscribe;
import org.eclipse.paho.mqttv5.common.packet.MqttUnsubAck;
import org.eclipse.paho.mqttv5.common.packet.MqttUnsubscribe;
import org.eclipse.paho.mqttv5.common.packet.MqttWireMessage;
import org.eclipse.paho.mqttv5.common.packet.util.VariableByteInteger;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebsocketVersion;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.NetClient;

public class ClientInternal {
	
	private MqttAsyncClient client = null;
	
	private static Object vertxLock = new Object(); // Simple lock
	private static Vertx vertx = null;
	private NetClient netclient = null;
	private HttpClient wsclient = null;
	public NetSocket socket = null;
	public WebSocket websocket = null;
	private boolean connected = false;
	private long reconnect_timer = 0;

	public Hashtable<Integer, MqttToken> out_hash_tokens = new Hashtable<Integer, MqttToken>();
	
	// Data that exists for the life of an MQTT session
	private SessionState sessionstate;
	
	// Data that exists for the life of an TCP connection
	private ConnectionState connectionstate;
	
	private ToDoQueue todoQueue;
	
	public ClientInternal(MqttAsyncClient client, MqttClientPersistence persistence) {
		this.client = client;
		// There is only one vert.x instance which we need to create
		synchronized (vertxLock) {
			if (vertx == null) {
				vertx = Vertx.vertx();
			}
		}
		connectionstate = new ConnectionState(this);
		todoQueue = new ToDoQueue(this, vertx, persistence, connectionstate);
		sessionstate = new SessionState(client, persistence, todoQueue);
	}
	
	public void close() {
		boolean cancelled = vertx.cancelTimer(reconnect_timer);
		sessionstate.setShouldBeConnected(false);
		todoQueue.close();
	}
	
	public MqttAsyncClient getClient() {
		return client;
	}
	
	public ConnectionState getConnectionState() {
		return connectionstate;
	}
	
	private void handleData(Buffer buffer, MqttToken connectToken) {
		MqttWireMessage msg = getPacket(buffer);
		
		while (msg != null) {
			connectionstate.registerInboundActivity();
			handlePacket(msg, connectToken);
			msg = getPacket(null);
		}
	}
	
	public static VariableByteInteger readVariableByteInteger(Buffer in) throws IOException {
		byte digit;
		int value = 0;
		int multiplier = 1;
		int count = 0;

		do {
			digit = in.getByte(count + 1);
			count++;
			value += ((digit & 0x7F) * multiplier);
			multiplier *= 128;
		} while ((digit & 0x80) != 0);

		if (value < 0 || value > MqttDataTypes.VARIABLE_BYTE_INT_MAX) {
			throw new IOException("This property must be a number between 0 and " + 
					MqttDataTypes.VARIABLE_BYTE_INT_MAX
					+ ". Read value was: " + value);
		}
		return new VariableByteInteger(value, count);
	}
	
	Buffer tempBuffer = Buffer.buffer();
	VariableByteInteger remlen = null;
	int packet_len = 0;

	private String[] serverURIs;
	
	public MqttWireMessage getPacket(Buffer buffer) {
		MqttWireMessage msg = null;
		try {
			if (tempBuffer.length() == 0) {
				if (buffer == null) {
					return null; // no more MQTT packets in the data
				}
				remlen = readVariableByteInteger(buffer);
				packet_len = remlen.getValue() + remlen.getEncodedLength() + 1;
				if (packet_len <= buffer.length()) { // we have at least 1 complete packet
					msg = MqttWireMessage.createWireMessage(buffer.getBytes(0, packet_len));
					// put any unused data into the temporary buffer
					if (buffer.length() > packet_len) {
						tempBuffer.appendBuffer(buffer, packet_len, buffer.length() - packet_len);
						remlen = null; // just in case there aren't enough bytes for the VBI
						remlen = readVariableByteInteger(tempBuffer);
						packet_len = remlen.getValue() + remlen.getEncodedLength() + 1;
					}
				} else {
					// incomplete packet
					tempBuffer.appendBuffer(buffer);
					return null;
				}
			} else {
				if (buffer != null) {
					tempBuffer.appendBuffer(buffer);
				}
				if (remlen == null) {
					remlen = readVariableByteInteger(tempBuffer);
					packet_len = remlen.getValue() + remlen.getEncodedLength() + 1;
				}
				if (tempBuffer.length() >= packet_len) {
					msg = MqttWireMessage.createWireMessage(tempBuffer.getBytes(0, packet_len));
					if (tempBuffer.length() > packet_len) {
						// leave unused data in the temporary buffer
						tempBuffer = tempBuffer.getBuffer(packet_len, tempBuffer.length());
						remlen = null; // just in case there aren't enough bytes for the VBI
						remlen = readVariableByteInteger(tempBuffer);
						packet_len = remlen.getValue() + remlen.getEncodedLength() + 1;
					} else {
						tempBuffer = Buffer.buffer();
					}
				} else {
					return null;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

		return msg;
	}
		
	private void handlePacket(MqttWireMessage msg, MqttToken connectToken) {
		try
		{		
			System.out.println("DEBUG - msg received "+msg.toString());
			if (msg instanceof MqttConnAck) {
				connectToken.setResponse(msg);
				connectionStart(msg.getProperties());
				connectToken.setComplete();	
			} else if (msg instanceof MqttSubAck || msg instanceof MqttPubAck
					|| msg instanceof MqttUnsubAck || msg instanceof MqttPubComp) {
				MqttToken acktoken = sessionstate.out_tokens.get(new Integer(msg.getMessageId()));
				if (acktoken != null) {
					if (msg instanceof MqttPubComp || msg instanceof MqttPubAck) {
						int[] a = acktoken.getReasonCodes();
						int[] b = msg.getReasonCodes();					
						int[] c = new int[a.length + b.length];
						System.arraycopy(a, 0, c, 0, a.length);
						System.arraycopy(b, 0, c, a.length, b.length);				
						acktoken.setReasonCodes(c);
					}
					acktoken.setResponse(msg);
				}
				// if the message has been restored from persistence, there are no tokens
				sessionstate.completeOutboundMessage(new Integer(msg.getMessageId()));
				if (acktoken != null) {
					acktoken.setComplete();
				} 
			} else if (msg instanceof MqttPublish) {
				IMqttMessageListener listener = sessionstate.getMessageListener(((MqttPublish) msg).getProperties().getSubscriptionIdentifier(), 
						((MqttPublish) msg).getTopicName());
				
				if (sessionstate.getInboundQoS2().get(msg.getMessageId()) == null) {
					// Don't deliver messages if that's already been attempted
					if (listener != null) {
						listener.messageArrived(((MqttPublish) msg).getTopicName(), 
								((MqttPublish) msg).getMessage());
					}	
					if (client.getCallback() != null) {
						client.getCallback().messageArrived(((MqttPublish) msg).getTopicName(), 
								((MqttPublish) msg).getMessage());
					}
				}
				if (((MqttPublish) msg).getQoS() == 0) {
				} else {
					Buffer outbuffer = null;
					if (((MqttPublish) msg).getQoS() == 1) {
						MqttPubAck puback = new MqttPubAck(MqttReturnCode.RETURN_CODE_SUCCESS, 
								msg.getMessageId(), null);
						outbuffer = Buffer.buffer(puback.serialize());
					} else if (((MqttPublish) msg).getQoS() == 2) {
						MqttPubRec pubrec = new MqttPubRec(MqttReturnCode.RETURN_CODE_SUCCESS, 
								msg.getMessageId(), null);
						outbuffer = Buffer.buffer(pubrec.serialize());
						sessionstate.addInboundQoS2((MqttPublish)msg);
					}
					if (websocket != null) {
						websocket.writeBinaryMessage(outbuffer, 
								res1 -> {
									if (!res1.succeeded()) {
										connectionstate.registerOutboundActivity();
									}
								});
					} else {
						socket.write(outbuffer,
								res1 -> {
									if (!res1.succeeded()) {
										connectionstate.registerOutboundActivity();
									}
								});
					}
				}
			} else if (msg instanceof MqttPubRec) {
				MqttToken acktoken = sessionstate.out_tokens.get(new Integer(msg.getMessageId()));
				MqttPubRel pubrel = new MqttPubRel(MqttReturnCode.RETURN_CODE_SUCCESS, 
						msg.getMessageId(),
						msg.getProperties());
				
				// change publish message to pubrel in retry queue 
				sessionstate.getRetryQueue().remove(msg.getMessageId());
				sessionstate.addRetryQueue(pubrel);
				
				if (acktoken != null) {
					// if the message has been restored from persistence, then there are no tokens
					// if the reason code is an error, then don't send the pubrel
					acktoken.setReasonCodes(msg.getReasonCodes());
				}
				if (websocket != null) {
					websocket.writeBinaryMessage(Buffer.buffer(pubrel.serialize()),
							res1 -> {
								if (!res1.succeeded()) {
									connectionstate.registerOutboundActivity();
								}
							});
				} else {
					socket.write(Buffer.buffer(pubrel.serialize()),
							res1 -> {
								if (!res1.succeeded()) {
									connectionstate.registerOutboundActivity();
								}
							});
				}
			} else if (msg instanceof MqttPubRel) {
				MqttPubComp pubcomp = new MqttPubComp(MqttReturnCode.RETURN_CODE_SUCCESS, 
						msg.getMessageId(),
						msg.getProperties());
				sessionstate.completeInboundQoS2Message(msg.getMessageId());
				if (websocket != null) {
						websocket.writeBinaryMessage(Buffer.buffer(pubcomp.serialize()),
								res1 -> {
									if (!res1.succeeded()) {
										connectionstate.registerOutboundActivity();
									}
								});
					} else {
						socket.write(Buffer.buffer(pubcomp.serialize()),
								res1 -> {
									if (!res1.succeeded()) {
										connectionstate.registerOutboundActivity();
									}
								});
				}
			} else if (msg instanceof MqttPingResp) {
				connectionstate.pingReceived();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public boolean isConnected() {
		return connected;
	}
	
	private NetClient createNetClient(URI uri) {
		NetClientOptions netopts = new NetClientOptions().setLogActivity(true);
		if (uri.getScheme().equals("ssl")) {
			netopts.setSsl(true);
			String temp = System.getProperty("javax.net.ssl.keyStore");
			if (temp != null) {
				JksOptions keyopts = new JksOptions();
				keyopts.setPath(temp);
				temp = System.getProperty("javax.net.ssl.keyStorePassword");
				if (temp != null) {
					keyopts.setPassword(temp);
				}
				netopts.setKeyStoreOptions(keyopts);
			}
			temp = System.getProperty("javax.net.ssl.trustStore");
			if (temp != null) {
				JksOptions trustopts = new JksOptions();
				trustopts.setPath(temp);
				temp = System.getProperty("javax.net.ssl.trustStorePassword");
				if (temp != null) {
					trustopts.setPassword(temp);
				}
				netopts.setTrustStoreOptions(trustopts);
			}
		}	
		netopts.setIdleTimeout(100);
		netopts.setConnectTimeout(100);		
		return vertx.createNetClient(netopts);
	}
	
	
	private HttpClient createWSClient(URI uri) {
		HttpClientOptions httpopts = new HttpClientOptions().setKeepAlive(false);
		httpopts.setMaxWebsocketFrameSize(25000000);
		if (uri.getScheme().equals("wss")) {
			httpopts.setSsl(true);
			httpopts.setForceSni(true);
			String temp = System.getProperty("javax.net.ssl.keyStore");
			if (temp != null) {
				JksOptions keyopts = new JksOptions();
				keyopts.setPath(temp);
				temp = System.getProperty("javax.net.ssl.keyStorePassword");
				if (temp != null) {
					keyopts.setPassword(temp);
				}
				httpopts.setKeyStoreOptions(keyopts);
			}
			temp = System.getProperty("javax.net.ssl.trustStore");
			if (temp != null) {
				JksOptions trustopts = new JksOptions();
				trustopts.setPath(temp);
				temp = System.getProperty("javax.net.ssl.trustStorePassword");
				if (temp != null) {
					trustopts.setPassword(temp);
				}
				httpopts.setTrustStoreOptions(trustopts);
			}
		}
		return vertx.createHttpClient(httpopts);
	}
	
	
	public void reconnect(MqttConnectionOptions connOpts) {
		connect(connOpts, new MqttToken(client), serverURIs, 0, null);
	}
	
	private int current_reconnect_delay = 0;
	
	public void connect(MqttConnectionOptions options, MqttToken userToken, 
			String[] serverURIs, final int index, Exception exc)  {
	
		todoQueue.setSize(client.getBufferOpts().getBufferSize());
		this.serverURIs = serverURIs;
		
		if (index >= serverURIs.length) {
			System.out.println("connect failed");
			userToken.setComplete();
			if (options.isAutomaticReconnect() && sessionstate.getShouldBeConnected()) {
				if (current_reconnect_delay == 0) {
					current_reconnect_delay = options.getAutomaticReconnectMinDelay();
				}
				current_reconnect_delay *= 2;
				if (current_reconnect_delay > options.getAutomaticReconnectMinDelay()) {
					current_reconnect_delay = options.getAutomaticReconnectMaxDelay();
				}
				reconnect_timer = vertx.setTimer(current_reconnect_delay, id -> {
					reconnect(options);
				});
			}
			return;
		}
		
		URI uri = null;
		try {
			uri = new URI(serverURIs[index]);
		} catch (Exception e) {
			e.printStackTrace();
			connect(options, userToken, serverURIs, index + 1, exc);
			return;
		}
		
		System.out.println(client.getClientId() + " Connecting to "+uri.toString());

		try {
			if (uri.getScheme().startsWith("ws")) {
				WSConnect(options, userToken, uri, serverURIs, index, exc);
			} else {
				TCPConnect(options, userToken, uri, serverURIs, index, exc);
			}
		
		} catch (Exception e) {
			e.printStackTrace();
			connect(options, userToken, serverURIs, index + 1, e);
		}
	}
	
	private void TCPConnect(MqttConnectionOptions options, MqttToken userToken, 
			URI uri, String[] serverURIs, final int index, Exception exc) {
		
		netclient = createNetClient(uri);
		netclient.connect(uri.getPort(), uri.getHost(), uri.getHost(), res -> {
		if (res.succeeded()) {
			socket = res.result();
			socket.handler(buffer -> {
				handleData(buffer, userToken);
			});
			socket.closeHandler(v -> {
				connectionEnd();
				System.out.println("The socket has been closed "+v);
				if (options.isAutomaticReconnect() && sessionstate.getShouldBeConnected()) {
					current_reconnect_delay = options.getAutomaticReconnectMinDelay();
					reconnect_timer = vertx.setTimer(current_reconnect_delay, id -> {
						reconnect(options);
					});
				}
			});
			socket.exceptionHandler(throwable -> {
				connectionEnd();
				System.out.println("The socket has an exception "+throwable.getMessage());
				if (userToken != null) {
					userToken.setComplete();
				}
			});
			MqttConnect connect = new MqttConnect(client.getClientId(), 
					options.getMqttVersion(),
					options.isCleanStart(),
					options.getKeepAliveInterval(),
					options.getConnectionProperties(), // properties
					options.getWillMessageProperties());  // will properties
			String userName = null;
			if ((userName = options.getUserName()) != null) {
				connect.setUserName(userName);
			}
			byte[] password = null;
			if ((userName = options.getUserName()) != null) {
				connect.setPassword(password);
			}
			try {
				//System.out.println("Sending connect "+getClient().getClientId());
				socket.write(Buffer.buffer(connect.serialize()),
					res1 -> {
						//System.out.println("Connect sent "+getClient().getClientId());
						if (res1.succeeded()) {
							connectionstate.registerOutboundActivity();
						} else {
							connect(options, userToken, serverURIs, index + 1, exc);
						}
					});
			} catch (Exception e) {
				e.printStackTrace();
				connect(options, userToken, serverURIs, index + 1, e);
			}
		} else {
			System.out.println("TCP connect failed "+getClient().getClientId());
			connect(options, userToken, serverURIs, index + 1, exc);
		}});
	}
	
	private void WSConnect(MqttConnectionOptions options, MqttToken userToken, 
			URI uri, String[] serverURIs, final int index, Exception exc) {
		
		MultiMap headers = new CaseInsensitiveHeaders();
		wsclient = createWSClient(uri);
		WebSocketConnectOptions wsopts = new WebSocketConnectOptions();
		wsopts.setPort(uri.getPort());
		wsopts.setHost(uri.getHost());
		//wsopts.setVersion(WebsocketVersion.V13);
		//wsopts.setHeaders(headers);
		wsopts.setURI("/mqtt");
		wsopts.addSubProtocol("mqtt");
		//wsclient.websocket(wsopts, websocket -> {
		wsclient.websocket(uri.getPort(), uri.getHost(), "/mqtt", headers, 
				WebsocketVersion.V13, "mqtt", 
				websocket -> {
			websocket.handler(buffer -> {
				handleData(buffer, userToken);
			});
			websocket.closeHandler(v -> {
				connectionEnd();
				System.out.println("The websocket has been closed "+v);
				if (options.isAutomaticReconnect() && sessionstate.getShouldBeConnected()) {
					current_reconnect_delay = options.getAutomaticReconnectMinDelay();
					reconnect_timer = vertx.setTimer(current_reconnect_delay, id -> {
						reconnect(options);
					});
				}
			});
			websocket.exceptionHandler(throwable -> {
				connectionEnd();
				System.out.println("The websocket has an exception "+throwable.getMessage());
				if (userToken != null) {
					userToken.setComplete();
				}
			});
			this.websocket = websocket;
			MqttConnect connect = new MqttConnect(client.getClientId(), 
					options.getMqttVersion(),
					options.isCleanStart(),
					options.getKeepAliveInterval(),
					options.getConnectionProperties(), // properties
					options.getWillMessageProperties());  // will properties
			String userName = null;
			if ((userName = options.getUserName()) != null) {
				connect.setUserName(userName);
			}
			byte[] password = null;
			if ((userName = options.getUserName()) != null) {
				connect.setPassword(password);
			}
			try {
				websocket.writeBinaryMessage(Buffer.buffer(connect.serialize()));
				connectionstate.registerOutboundActivity();
			} catch (Exception e) {
				e.printStackTrace();
				connect(options, userToken, serverURIs, index + 1, e);
			}
		});
	}
	
	public void disconnect(int reasonCode, MqttProperties disconnectProperties, MqttToken token) {
		sessionstate.setShouldBeConnected(false);
		if (!isConnected()) {
			connectionEnd();
			token.setComplete();
			return;  // already disconnected
		}
		try {
			MqttDisconnect disconnect = new MqttDisconnect(reasonCode, disconnectProperties);
			if (websocket != null) {
				websocket.closeHandler(v -> {
					connectionstate.registerOutboundActivity();
					connectionEnd();
					token.setComplete();
				});
				websocket.writeBinaryMessage(Buffer.buffer(disconnect.serialize()));
				websocket.close();
			} else {
				socket.write(Buffer.buffer(disconnect.serialize()),
						res1 -> {
							// remove closeHandler to avoid any chance of recursive handler calls
							socket.closeHandler(v -> {});   
							if (res1.failed()) {
								System.out.println("write failed");
							}
							if (socket != null) {
								socket.close();
								socket = null;
							}
							connectionstate.registerOutboundActivity();
							connectionEnd();
							token.setComplete();
						});
			}
		} catch (Exception e) {
			//e.printStackTrace();
		}
	}
	
	private void connectionStart(MqttProperties properties) {
		//System.out.println("DEBUG - connectionStart");
		connectionstate = new ConnectionState(this);
		if (client.getConnectOpts().isCleanStart()) {
			sessionstate.clear();
		}
		sessionstate.setShouldBeConnected(true);
		connected = true;
		String assigned_clientid = properties.getAssignedClientIdentifier();	
		if (assigned_clientid != null) {
			client.setClientId(assigned_clientid);
		}
		try {
			if (client.getConnectOpts().getKeepAliveInterval() > 0) {
				long kid = vertx.setPeriodic(client.getConnectOpts().getKeepAliveInterval() * 1000, id -> {
					connectionstate.keepAlive(client.getConnectOpts().getKeepAliveInterval());
				});
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		retryMessages();
		//System.out.println("**** Connected "+sessionstate.getInboundQoS2().size());
	}
	
	private void connectionEnd() {
		todoQueue.pause();
		connected = false;
		if (client.getConnectOpts().getSessionExpiryInterval() == null || 
				client.getConnectOpts().getSessionExpiryInterval() == 0) {
			connectionstate = new ConnectionState(this);
			sessionstate.clear();
		}
	}
	
	public void retryMessages() {
		
		Enumeration<Integer> keys = sessionstate.getRetryQueue().getKeys();
		
		retryMessage(keys);
		//System.out.println("====== retry queue " +sessionstate.getRetryQueue().size());
	}
	
	
	public void retryMessage(Enumeration<Integer> keys) {	
		if (keys.hasMoreElements()) {
			PersistedBuffer retryQueue = sessionstate.getRetryQueue();
			Integer key = keys.nextElement();
			byte[] message = null;
			
			try {
				MqttWireMessage wireMessage = retryQueue.get(key);
				wireMessage.setDuplicate(true); // when retrying a message, set the dup flag
				message = wireMessage.serialize();
			} catch (MqttException e) {
				e.printStackTrace();
				return;
			}
			// TODO: this doesn't cater for the receive maximum setting, yet
			if (websocket != null) {
				websocket.writeBinaryMessage(Buffer.buffer(message),
						res1 -> {
							if (res1.succeeded()) {
								connectionstate.registerOutboundActivity();
								retryMessage(keys);
							} else {
								System.out.println("Retry message fail");
							}
						});
				connectionstate.registerOutboundActivity();
			} else {
				//System.out.println("==== retrying "+retryQueue.get(key));
				socket.write(Buffer.buffer(message),
						res1 -> {
							if (res1.succeeded()) {
								//System.out.println("Retry message succeeded "+ key);
								connectionstate.registerOutboundActivity();
								retryMessage(keys);
							} else {
								System.out.println("Retry message fail");
							}
						});
			}
		} else {
			//System.out.println("Resume todo queue");
			todoQueue.resume(); 
		}
	}
	
	
	public void subscribe(MqttSubscription[] subscriptions, MqttProperties subscriptionProperties,
			MqttToken token) throws MqttException {
		
		int msgid = sessionstate.getNextMessageId(); // throws exception if none available
		
		MqttSubscribe subscribe = new MqttSubscribe(subscriptions, subscriptionProperties);
		token.setRequestMessage(subscribe);
		subscribe.setMessageId(msgid);
		sessionstate.out_tokens.put(new Integer(msgid), token);
		todoQueue.add(subscribe, token);
	}
	
	public void unsubscribe(String[] topicFilters, MqttProperties unsubscribeProperties,
			MqttToken token) throws MqttException {

		int msgid = sessionstate.getNextMessageId(); // throws exception if none available
		
		MqttUnsubscribe unsubscribe = new MqttUnsubscribe(topicFilters, unsubscribeProperties);
		token.setRequestMessage(unsubscribe);
		unsubscribe.setMessageId(msgid);
		sessionstate.out_tokens.put(new Integer(msgid), token);
		todoQueue.add(unsubscribe, token);
	}
		
	public void publish(String topic, MqttMessage message, MqttToken token) throws MqttException {
		
		// if we are not connected, and offline buffering is not enabled, then we return a failure
		if (!client.getBufferOpts().isBufferEnabled() && !client.isConnected()) {
			throw new MqttException(MqttClientException.REASON_CODE_CLIENT_NOT_CONNECTED);
		}
		
		int msgid = -1;
		if (message.getQos() > 0) {
			msgid = sessionstate.getNextMessageId(); // throws exception if none available
		}
		
		MqttPublish publish = new MqttPublish(topic, message, message.getProperties());
		token.setRequestMessage(publish);
		if (message.getQos() > 0) {
			publish.setMessageId(msgid); 
			sessionstate.out_tokens.put(new Integer(msgid), token);
		} else {
			// QoS 0 messages have no message id
			out_hash_tokens.put(new Integer(token.hashCode()), token);
		}
		
		publish = connectionstate.setTopicAlias(publish);
		todoQueue.add(publish, token);	
	}
	
	
	public SessionState getSessionState() {
		return sessionstate;
	}
	
	public String getClientId() {
		return sessionstate.getClientId();
	}

	public void setClientId(String clientId) {
		sessionstate.setClientId(clientId);
	}

	public MqttWireMessage getBufferedMessage(int index) {
		return todoQueue.getMessage(index);
	}
	
	public MqttWireMessage deleteBufferedMessage(int index) {
		return todoQueue.removeMessage(index);
	}
	
	public int getBufferedMessageCount() {
		return todoQueue.getQueued();
	}
	
	public void removeMessageListener(Integer subId, String topic) { 
		sessionstate.removeMessageListener(subId, topic);
	}
	
	public void setMessageListener(Integer subId, String topic, IMqttMessageListener messageListener) {
		sessionstate.setMessageListener(subId, topic, messageListener);
	}

}