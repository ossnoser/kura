package org.eclipse.kura.linux.net.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.core.linux.util.LinuxProcessUtil;
import org.eclipse.kura.core.net.WifiAccessPointImpl;
import org.eclipse.kura.core.net.util.NetworkUtil;
import org.eclipse.kura.core.util.ProcessUtil;
import org.eclipse.kura.net.wifi.WifiAccessPoint;
import org.eclipse.kura.net.wifi.WifiMode;
import org.eclipse.kura.net.wifi.WifiSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class iwScanTool {
	
	private static final Logger s_logger = LoggerFactory.getLogger(iwScanTool.class);

	private static final Object s_lock = new Object();
	private String m_ifaceName;
	private ExecutorService m_executor;
	private static Future<?>  m_task;

	private int m_timeout;
	private Process m_proccess;
	private boolean m_status;
	private String m_errmsg;
	
	public iwScanTool() {
		m_timeout = 10;
	}
	
	public iwScanTool(String ifaceName) {
		this();
		m_ifaceName = ifaceName;
		m_errmsg = "";
		m_status = false;
	}
	
	public iwScanTool(String ifaceName, int tout) {
		this(ifaceName);
		m_timeout = tout;
	}
	
	public List<WifiAccessPoint> scan() throws KuraException {
		
		List<WifiAccessPoint> wifiAccessPoints = new ArrayList<WifiAccessPoint>();
		synchronized (s_lock) {
			StringBuilder sb = new StringBuilder();
		    
			Process pr = null;
			try {
				if(!LinuxNetworkUtil.isUp(m_ifaceName)) {
				    // activate the interface
					sb.append("ip link set ").append(m_ifaceName).append(" up");
				    pr = ProcessUtil.exec(sb.toString());
				 
				    // remove the previous ip address (needed on mgw)
				    sb = new StringBuilder();
					sb.append("ip addr flush dev ").append(m_ifaceName);
				    pr = ProcessUtil.exec(sb.toString());			    
				}
			} catch (Exception e) {
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
			} finally {
				if (pr != null) {
					ProcessUtil.destroy(pr);
				}
			}
	
			long timerStart = System.currentTimeMillis();
			m_executor = Executors.newSingleThreadExecutor();
			m_task = m_executor.submit(new Runnable() {
				@Override
				public void run() {
					int stat = -1;
					m_proccess = null;
					StringBuilder sb = new StringBuilder();
					sb.append("iw dev ").append(m_ifaceName).append(" scan");
					s_logger.info("scan() :: executing: {}", sb.toString());
					m_status = false;
					try {
						m_proccess = ProcessUtil.exec(sb.toString());
						stat = m_proccess.waitFor();
						s_logger.info("scan() :: {} command returns status={}", sb.toString(), stat);
						if (stat == 0) {
							m_status = true;
						} else {
							s_logger.error("scan() :: failed to execute {} error code is {}", sb.toString(), stat);
							s_logger.error("scan() :: STDERR: " + LinuxProcessUtil.getInputStreamAsString(m_proccess.getErrorStream()));
						}	
					} catch (Exception e) {
						m_errmsg = "exception executing scan command";
						e.printStackTrace();
					}
				}
			});
			
			while (!m_task.isDone()) {
				if (System.currentTimeMillis() > timerStart+m_timeout*1000) {
					sb = new StringBuilder();
					sb.append("iw dev ").append(m_ifaceName).append(" scan");
					try {
						int pid = LinuxProcessUtil.getPid(sb.toString());
						if (pid >= 0) {
							LinuxProcessUtil.kill(pid);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
					m_task.cancel(true);
					m_task = null;
					m_errmsg = "timeout executing scan command";
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
				}
			}
			 
			if ((m_status == false) || (m_proccess == null)) {
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, m_errmsg);
			}
			
			s_logger.info("scan() :: the 'iw scan' command executed successfully, parsing output ...");
			try {
				wifiAccessPoints = parse();
			} catch (Exception e) {
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e, "error parsinf scan results");
			} finally {
				s_logger.info("scan() :: destroing scan proccess ...");
				ProcessUtil.destroy(m_proccess);
				m_proccess = null;
				
				s_logger.info("scan() :: Terminating WifiMonitor Thread ...");
				m_executor.shutdownNow();
				try {
					m_executor.awaitTermination(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					s_logger.warn("Interrupted", e);
				}
				s_logger.info("scan() :: 'iw scan' thread terminated? - {}", m_executor.isTerminated());
				m_executor = null;
			}
		}
	
		return wifiAccessPoints;
	}
	
	
	private List<WifiAccessPoint> parse() throws Exception {
		
		List<WifiAccessPoint> wifiAccessPoints = new ArrayList<WifiAccessPoint>();
		
		//get the output
		BufferedReader br = new BufferedReader(new InputStreamReader(m_proccess.getInputStream()));
		String line = null;
		
		String ssid = null;
		List<Long> bitrate = null;
		long frequency = -1;
		byte[] hardwareAddress = null;
		WifiMode mode = null;
		EnumSet<WifiSecurity> rsnSecurity = null;
		int strength = -1;
		EnumSet<WifiSecurity> wpaSecurity = null;
		List<String> capabilities = null;
		
		while((line = br.readLine()) != null) {
			if(line.contains("BSS ") && !line.contains("* OBSS")) {
				//new AP
				if(ssid != null) {
					WifiAccessPointImpl wifiAccessPoint = new WifiAccessPointImpl(ssid);
					wifiAccessPoint.setBitrate(bitrate);
					wifiAccessPoint.setFrequency(frequency);
					wifiAccessPoint.setHardwareAddress(hardwareAddress);
					wifiAccessPoint.setMode(WifiMode.MASTER);				//FIME - is this right? - always MASTER - or maybe AD-HOC too?
					wifiAccessPoint.setRsnSecurity(rsnSecurity);
					wifiAccessPoint.setStrength(strength);
					wifiAccessPoint.setWpaSecurity(wpaSecurity);
					if ((capabilities != null) && (capabilities.size() > 0)) {
						wifiAccessPoint.setCapabilities(capabilities);
					}
					wifiAccessPoints.add(wifiAccessPoint);
				}
				
				//reset
				ssid = null;
				bitrate = null;
				frequency = -1;
				hardwareAddress = null;
				mode = null;
				rsnSecurity = null;
				strength = -1;
				wpaSecurity = null;
				capabilities = null;
				
				//parse out the MAC
				StringTokenizer st = new StringTokenizer(line, " ");
				st.nextToken(); //eat BSS
				String macAddressString = st.nextToken();
				if(macAddressString != null) {
					hardwareAddress = NetworkUtil.macToBytes(macAddressString);				
				}
			} else if(line.contains("freq: ")) {
				StringTokenizer st = new StringTokenizer(line, " ");
				st.nextToken();	//eat freq:
				frequency = Long.parseLong(st.nextToken());
			} else if(line.contains("SSID: ")) {
				ssid = line.trim().substring(5).trim();
			} else if(line.contains("RSN:")) {
				rsnSecurity = EnumSet.noneOf(WifiSecurity.class);
				boolean foundGroup = false;
				boolean foundPairwise = false;
				boolean foundAuthSuites = false;
				while((line = br.readLine()) != null) {
					if(line.contains("Group cipher:")) {
						foundGroup = true;
						if(line.contains("CCMP")) {
							rsnSecurity.add(WifiSecurity.GROUP_CCMP);
						}
						if(line.contains("TKIP")) {
							rsnSecurity.add(WifiSecurity.GROUP_TKIP);
						}
						if(line.contains("WEP104")) {
							rsnSecurity.add(WifiSecurity.GROUP_WEP104);
						}
						if(line.contains("WEP40")) {
							rsnSecurity.add(WifiSecurity.GROUP_WEP40);
						}
					} else if(line.contains("Pairwise ciphers:")) {
						foundPairwise = true;
						if(line.contains("CCMP")) {
							rsnSecurity.add(WifiSecurity.PAIR_CCMP);
						}
						if(line.contains("TKIP")) {
							rsnSecurity.add(WifiSecurity.PAIR_TKIP);
						}
						if(line.contains("WEP104")) {
							rsnSecurity.add(WifiSecurity.PAIR_WEP104);
						}
						if(line.contains("WEP40")) {
							rsnSecurity.add(WifiSecurity.PAIR_WEP40);
						}
					} else if(line.contains("Authentication suites:")) {
						foundAuthSuites = true;
						if(line.contains("802_1X")) {
							rsnSecurity.add(WifiSecurity.KEY_MGMT_802_1X);
						}
						if(line.contains("PSK")) {
							rsnSecurity.add(WifiSecurity.KEY_MGMT_PSK);
						}
					} else {
						s_logger.debug("Ignoring line in RSN: " + line);
					}
					
					if(foundGroup && foundPairwise && foundAuthSuites) {
						break;
					}
				}
			} else if(line.contains("WPA:")) {
				wpaSecurity = EnumSet.noneOf(WifiSecurity.class);
				boolean foundGroup = false;
				boolean foundPairwise = false;
				boolean foundAuthSuites = false;
				while((line = br.readLine()) != null) {
					if(line.contains("Group cipher:")) {
						foundGroup = true;
						if(line.contains("CCMP")) {
							wpaSecurity.add(WifiSecurity.GROUP_CCMP);
						}
						if(line.contains("TKIP")) {
							wpaSecurity.add(WifiSecurity.GROUP_TKIP);
						}
						if(line.contains("WEP104")) {
							wpaSecurity.add(WifiSecurity.GROUP_WEP104);
						}
						if(line.contains("WEP40")) {
							wpaSecurity.add(WifiSecurity.GROUP_WEP40);
						}
					} else if(line.contains("Pairwise ciphers:")) {
						foundPairwise = true;
						if(line.contains("CCMP")) {
							wpaSecurity.add(WifiSecurity.PAIR_CCMP);
						}
						if(line.contains("TKIP")) {
							wpaSecurity.add(WifiSecurity.PAIR_TKIP);
						}
						if(line.contains("WEP104")) {
							wpaSecurity.add(WifiSecurity.PAIR_WEP104);
						}
						if(line.contains("WEP40")) {
							wpaSecurity.add(WifiSecurity.PAIR_WEP40);
						}
					} else if(line.contains("Authentication suites:")) {
						foundAuthSuites = true;
						if(line.contains("802_1X")) {
							wpaSecurity.add(WifiSecurity.KEY_MGMT_802_1X);
						}
						if(line.contains("PSK")) {
							wpaSecurity.add(WifiSecurity.KEY_MGMT_PSK);
						}
					} else {
						s_logger.debug("Ignoring line in WPA: " + line);
					}
					
					if(foundGroup && foundPairwise && foundAuthSuites) {
						break;
					}
				}
			} else if(line.contains("Supported rates: ")) {
				//Supported rates: 1.0* 2.0* 5.5* 11.0* 18.0 24.0 36.0 54.0
				if(bitrate == null) {
					bitrate = new ArrayList<Long>();
				}
				StringTokenizer st = new StringTokenizer(line, " *");
				while(st.hasMoreTokens()) {
					String token = st.nextToken();
					if(!(token.contains("Supported") || token.contains("rates:"))) {
						bitrate.add((long) (Float.parseFloat(token) * 1000000));
					}
				}
			} else if(line.contains("Extended supported rates: ")) {
				//Extended supported rates: 6.0 9.0 12.0 48.0 
				if(bitrate == null) {
					bitrate = new ArrayList<Long>();
				}
				StringTokenizer st = new StringTokenizer(line, " *");
				while(st.hasMoreTokens()) {
					String token = st.nextToken();
					if(!(token.contains("Extended") || token.contains("supported") || token.contains("rates:"))) {
						bitrate.add((long) (Float.parseFloat(token) * 1000000));
					}
				}
			} else if(line.contains("signal:")) {
				try {
					//signal: -56.00 dBm
					StringTokenizer st = new StringTokenizer(line, " ");
					st.nextToken(); //eat signal:
					final String strengthRaw = st.nextToken();
					if (strengthRaw.contains("/")) {
						// Could also be of format 39/100
						final String[] parts = strengthRaw.split("/");
						strength = (int) Float.parseFloat(parts[0]);
					} else {
						strength = Math.abs((int)Float.parseFloat(strengthRaw));
					}
				} catch (RuntimeException e) {
					s_logger.debug("Cannot parse signal strength " + line);
				}
			} else if (line.contains("capability:")) {
				capabilities = new ArrayList<String>();
				line = line.substring("capability:".length()).trim();
				StringTokenizer st = new StringTokenizer(line, " ");
				while (st.hasMoreTokens()) {
					capabilities.add(st.nextToken());
				}
			}
		}
		
		//store the last one
		if(ssid != null) {
			WifiAccessPointImpl wifiAccessPoint = new WifiAccessPointImpl(ssid);
			wifiAccessPoint.setBitrate(bitrate);
			wifiAccessPoint.setFrequency(frequency);
			wifiAccessPoint.setHardwareAddress(hardwareAddress);
			wifiAccessPoint.setMode(mode);
			wifiAccessPoint.setRsnSecurity(rsnSecurity);
			wifiAccessPoint.setStrength(strength);
			wifiAccessPoint.setWpaSecurity(wpaSecurity);
			if ((capabilities != null) && (capabilities.size() > 0)) {
				wifiAccessPoint.setCapabilities(capabilities);
			}
			wifiAccessPoints.add(wifiAccessPoint);
		}
		return wifiAccessPoints;
	}
}
