# API AIDL d'ivi-services (référence)

Générée à partir de `ivi-services.apk` 3.0.0.ac8257.6a605cd0.20250611 (jadx). Chaque service est lié par
`new Intent("com.jancar.services.action.<nom>").setPackage("com.jancar.services")` ; **aucun n'exige de
permission** et aucun ne vérifie l'appelant. Le numéro est le code de transaction binder (`IBinder.transact`).

Les interfaces `*Callback` sont implémentées par les apps clientes et appelées par le service.

## `com.jancar.services.audio.IAudio` (54 méthodes)

| # | Méthode |
|---|---|
| 1 | `void audioControlCMD(int id, int[] ints, float[] floats, String[] strs)` |
| 2 | `void registerCallback(IAudioCallback callback)` |
| 3 | `void unRegisterCallback(IAudioCallback callback)` |
| 4 | `boolean isParamAvailable(int id)` |
| 5 | `int getParamMinValue(int id)` |
| 6 | `int getParamMaxValue(int id)` |
| 7 | `int getParamDefaultValue(int id)` |
| 8 | `int getParam(int id)` |
| 9 | `void setParam(int id, int value)` |
| 10 | `int[] getEqGains(int eqMode)` |
| 11 | `boolean isBuildInPreVolumeAvailable(int channel)` |
| 12 | `float getBuildInPreVolumeMinValue(int channel)` |
| 13 | `float getBuildInPreVolumeMaxValue(int channel)` |
| 14 | `float getBuildInPreVolumeDefaultValue(int channel)` |
| 15 | `float getBuildInPreVolumeValue(int channel)` |
| 16 | `void setBuildInPreVolumeValue(int channel, float value)` |
| 17 | `void resetBuildInPreVolumeValue()` |
| 18 | `boolean isSecondaryBuildInPreVolumeAvailable(int channel)` |
| 19 | `float getSecondaryBuildInPreVolumeMinValue(int channel)` |
| 20 | `float getSecondaryBuildInPreVolumeMaxValue(int channel)` |
| 21 | `float getSecondaryBuildInPreVolumeDefaultValue(int channel)` |
| 22 | `float getSecondaryBuildInPreVolumeValue(int channel)` |
| 23 | `void setSecondaryBuildInPreVolumeValue(int channel, float value)` |
| 24 | `void resetSecondaryBuildInPreVolumeValue()` |
| 25 | `boolean isMasterAudioDeviceAvailable()` |
| 26 | `float getMasterVolumeGainMinValue()` |
| 27 | `float getMasterVolumeGainMaxValue()` |
| 28 | `float getMasterVolumeGainDefaultValue(int volume)` |
| 29 | `float getMasterVolumeGainValue(int volume)` |
| 30 | `void setMasterVolumeGainValue(int volume, float value)` |
| 31 | `void resetMasterVolumeGainValue()` |
| 32 | `boolean isSecondaryAudioDeviceAvailable()` |
| 33 | `float getSecondaryVolumeGainMinValue()` |
| 34 | `float getSecondaryVolumeGainMaxValue()` |
| 35 | `float getSecondaryVolumeGainDefaultValue(int volume)` |
| 36 | `float getSecondaryVolumeGainValue(int volume)` |
| 37 | `void setSecondaryVolumeGainValue(int volume, float value)` |
| 38 | `void resetSecondaryVolumeGainValue()` |
| 39 | `void showVolumeBar()` |
| 40 | `void hideVolumeBar()` |
| 41 | `void toggleVolumeBar()` |
| 42 | `void showSecondaryVolumeBar()` |
| 43 | `int getActiveVolumeId()` |
| 44 | `int getMasterVolumeMin()` |
| 45 | `int getMasterVolumeMax()` |
| 46 | `int getSecondaryVolumeMin()` |
| 47 | `int getSecondaryVolumeMax()` |
| 48 | `void requestInternalShortMute(int muteDurationMs)` |
| 49 | `void setAnalogMediaVolumePercent(int percent)` |
| 50 | `int getMasterAudioChannel()` |
| 51 | `void setChipParam(int chipId, int paramId, double v0, double v1, double v2, double v3)` |
| 52 | `void addExpertAudioEffect(int effect, String pathName, boolean apply)` |
| 53 | `int[] getAvailableExpertAudioEffects()` |
| 54 | `String getExpertAudioEffectFile(int effect)` |

## `com.jancar.services.audio.IAudioCallback` (5 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onVolumeChanged(int id, int value)` |
| 2 | `void onMuteChanged(boolean mute, int source)` |
| 3 | `void onVolumeBar(int id, int value, int maxValue)` |
| 4 | `void onSecondaryMuteChanged(boolean mute)` |
| 5 | `void onMasterAudioChannelChange(int channel)` |

## `com.jancar.services.avin.IAVIn` (21 méthodes)

| # | Méthode |
|---|---|
| 1 | `void open(int avId, IAVInCallback callback, String packageName)` |
| 2 | `void close(int avId)` |
| 3 | `boolean isOpen(int avId)` |
| 4 | `boolean isParamAvailable(int id)` |
| 5 | `int getParamMinValue(int id)` |
| 6 | `int getParamMaxValue(int id)` |
| 7 | `int getParamDefaultValue(int id)` |
| 8 | `int getParam(int id)` |
| 9 | `void setParam(int id, int value)` |
| 10 | `int getVideoSignal(int avId)` |
| 11 | `boolean isVideoPermit()` |
| 12 | `void unRegisterCallback(IAVInCallback callback)` |
| 13 | `void setAndroidCameraOpenPrepared(int avId)` |
| 14 | `void setAndroidCameraOpen(int avId, boolean isOpen)` |
| 15 | `boolean getSourcePlugin(int avId)` |
| 16 | `void startTempSound(int avId, int zone, boolean mix)` |
| 17 | `void endTempSound(int avId)` |
| 18 | `void registerCallback(IAVInCallback callback)` |
| 19 | `boolean controlTV(int control)` |
| 20 | `boolean sendTouch(int x, int y)` |
| 21 | `void setAHDInput(int avId)` |

## `com.jancar.services.avin.IAVInCallback` (10 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onVideoSignalChanged(int avId, int signal)` |
| 2 | `void onVideoPermitChanged(boolean show)` |
| 3 | `void stop()` |
| 4 | `void resume()` |
| 5 | `void next()` |
| 6 | `void prev()` |
| 7 | `void quitApp()` |
| 8 | `void select(int index)` |
| 9 | `void onCvbsTypeChanged(int avId, int cvbsType)` |
| 10 | `void onSourcePluginChanged(int avId, boolean plugin)` |

## `com.jancar.services.car.IAirControlUpgradeCallback` (4 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onSuccess(String airVersion)` |
| 2 | `void onUpgradeState(int state)` |
| 3 | `void onFailure(int errorCode)` |
| 4 | `void onProgress(int progress)` |

## `com.jancar.services.car.ICar` (74 méthodes)

| # | Méthode |
|---|---|
| 1 | `String getProtocolMcuVersion()` |
| 2 | `int getCarId()` |
| 3 | `void registerCallback(ICarCallback callback)` |
| 4 | `void unRegisterCallback(ICarCallback callback)` |
| 5 | `void registerRealTimeInfo(int id, ICarCallback callback)` |
| 6 | `void unRegisterRealTimeInfo(int id, ICarCallback callback)` |
| 7 | `void registerPassthroughDataCallback(IPassthroughDataCallback callback)` |
| 8 | `void unRegisterPassthroughDataCallback(IPassthroughDataCallback callback)` |
| 9 | `float getRealTimeInfo(int id)` |
| 10 | `int getCcdStatus()` |
| 11 | `int getHandbrakeStatus()` |
| 12 | `int getDoorStatusMask()` |
| 13 | `int getLightStatusMask()` |
| 14 | `boolean getHeadLightStatus()` |
| 15 | `int getClimate(int id)` |
| 16 | `void setClimate(int id, int value)` |
| 17 | `int getTires(int id)` |
| 18 | `byte[] getCarSettingBytes()` |
| 19 | `void setCarSetting(int id, int value)` |
| 20 | `void sendPassthroughData(byte id, byte[] datas)` |
| 21 | `byte[] getRadarDistanceBytes()` |
| 22 | `byte[] getRadarWarmingBytes()` |
| 23 | `void needRadarValue()` |
| 24 | `int getOutsideTempRawValue()` |
| 25 | `float getTrip(int id, int index)` |
| 26 | `float getExtraState(int id)` |
| 27 | `void upgradeMcu(String filePath, IMcuUpgradeCallback callback)` |
| 28 | `void setCcdPower(boolean on)` |
| 29 | `void disableFastReverse()` |
| 30 | `boolean isInFastReverse()` |
| 31 | `void setExtraDevice(int carId, int deviceId, byte[] extraDeviceData)` |
| 32 | `void setExtraAudioParameters(byte[] extraAudioData)` |
| 33 | `void requestExtraDeviceEvent()` |
| 34 | `void setCmdParam(int id, byte[] paramData)` |
| 35 | `byte[] getCmdParams(int id)` |
| 36 | `void requestCmdParamEvent()` |
| 37 | `void sendTouchClick(int x, int y)` |
| 38 | `int getMaintenanceMileage(int id)` |
| 39 | `int getMaintenanceDays(int id)` |
| 40 | `String getCarVIN()` |
| 41 | `int getPairKeyNumber()` |
| 42 | `int[] getReportArray(int carId, int reportType)` |
| 43 | `int getAutoPark()` |
| 44 | `byte[] getEnergyFlowData()` |
| 45 | `void setCarLedKey(int key, int pushType)` |
| 46 | `void setADKey(int channel, int key)` |
| 47 | `void setClusterParam(byte[] params)` |
| 48 | `void setTouch(int x, int y, int type)` |
| 49 | `void pauseHeartbeat()` |
| 50 | `void setAccOffUintOn()` |
| 51 | `void requestCmdTpmsEvent()` |
| 52 | `boolean isAccOn()` |
| 53 | `String getHardwareVersionString()` |
| 54 | `void sendKeyToMcu(int key)` |
| 55 | `void clearClimate(int id, int value)` |
| 56 | `void openBeepSound(int distance, int direction)` |
| 57 | `void closeBeepSound()` |
| 58 | `void setBackLight(int brightness)` |
| 59 | `void setShowListInfo(int listType, List<String> list, int position, int cursorId)` |
| 60 | `void setTalkingTime(int hour, int minute, int second)` |
| 61 | `void upgradeCluster(String filePath, String updateVersion, IClusterUpgradeCallback callback)` |
| 62 | `int getCarModelConfig()` |
| 63 | `String getClusterVersion()` |
| 64 | `void upgradeAirControl(String filePath, String updateVersion, IAirControlUpgradeCallback callback)` |
| 65 | `int getPowerStatus()` |
| 66 | `String getProtocolCanVersion()` |
| 67 | `void setFrontRadarDataChange(boolean frontRadarDataChange)` |
| 68 | `void setAlcoholPower(boolean on)` |
| 69 | `double getAlcoholDetectionValue()` |
| 70 | `void registerYadiCallback(IYadiGpioDataCallback callback)` |
| 71 | `void unRegisterYadiCallback(IYadiGpioDataCallback callback)` |
| 72 | `int getUsbPinCode()` |
| 73 | `void setUsbPinCode(int pinCode)` |
| 74 | `int getDvrSignalValue()` |

## `com.jancar.services.car.ICarCallback` (34 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onMcuVersion(String version)` |
| 2 | `void onAccChanged(boolean on)` |
| 3 | `void onCcdChanged(int status)` |
| 4 | `void onHandbrakeChanged(boolean hold)` |
| 5 | `void onDoorChanged(int changeMask, int statusMask)` |
| 6 | `void onLightChanged(int changeMask, int statusMask)` |
| 7 | `void onHeadLightChanged(boolean on)` |
| 8 | `void onClimateChanged(int id, int rawValue)` |
| 9 | `void onOutsideTempChanged(int rawValue)` |
| 10 | `void onKeyPushed(int id, int type)` |
| 11 | `void onAlertMessage(int messageCode)` |
| 12 | `void onTripChanged(int id, int index, float value)` |
| 13 | `void onRealTimeInfoChanged(int id, float value)` |
| 14 | `void onExtraStateChanged(int id, float value)` |
| 15 | `void onRadarChanged(int radarType, byte[] radarData)` |
| 16 | `void onCarSettingChanged(int carId, byte[] settingData)` |
| 17 | `void onExtraDeviceChanged(int carId, int deviceId, byte[] extraDeviceData)` |
| 18 | `void onCmdParamChanged(int id, byte[] paramData)` |
| 19 | `void onMaintenanceChanged(int id, int mileage, int days)` |
| 20 | `void onCarVINChanged(String VIN, int keyNumber)` |
| 21 | `void onCarReportChanged(int carid, int type, int[] list)` |
| 22 | `void onAutoParkChanged(int status)` |
| 23 | `void onEnergyFlowChanged(int battery, int engineToTyre, int engineToMotor, int motorToTyre, int motorToBattery)` |
| 24 | `void onFastReverseChanged(boolean on)` |
| 25 | `void onADKeyChanged(int channel, int value, int highHindered, int midHindered, int lowHindered)` |
| 26 | `void onClusterMessage(byte[] datas)` |
| 27 | `void onTirePressureChanged(int id, int rawValue, int extraValue, int dotType)` |
| 28 | `void onEventHardwareVersion(int status, String hardware, String supplier, String ecn, String date, String manufactureDate)` |
| 29 | `void onMaintainWarning(boolean show)` |
| 30 | `void onBackLightChanged(int brightness)` |
| 31 | `void onCarModelChange(int carModel)` |
| 32 | `void onPowerChange(int status)` |
| 33 | `void onUsbPinCodeChange(int pinCode)` |
| 34 | `void onDvrSignalValueChange(int value)` |

## `com.jancar.services.car.IClusterUpgradeCallback` (4 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onSuccess(String clusterVersion)` |
| 2 | `void onUpgradeState(int state)` |
| 3 | `void onFailure(int errorCode)` |
| 4 | `void onProgress(int progress)` |

## `com.jancar.services.car.IMcuUpgradeCallback` (4 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onSuccess(String mcuVersion)` |
| 2 | `void onWaitMcuReboot()` |
| 3 | `void onFailure(int errorCode)` |
| 4 | `void onProgress(int progress)` |

## `com.jancar.services.car.IPassthroughDataCallback` (8 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onPassthroughData(byte[] datas, int len)` |
| 2 | `void onMediaStateChanged(int state, int mMediaType, int mPosition, int mDuration)` |
| 3 | `void onMediaInfoChanged(int mMediaType, String mName, String mInfo, int mIndex, int mTotalCount)` |
| 4 | `void onVolumeChanged(int mId, int mValue, int mMax)` |
| 5 | `void onMuteStateChanged(boolean mMute, int mSource)` |
| 6 | `void onBluetoothConnectStatus(int mStatus, String mAddr, String mName)` |
| 7 | `void onBluetoothCallStatus(String mPhoneNumber, String mContactName, int mStatus, boolean mInsertSql)` |
| 8 | `void onEventDiskChanged(String mPath, int mType)` |

## `com.jancar.services.car.IYadiGpioDataCallback` (1 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onDataUpdate(byte[] data)` |

## `com.jancar.services.cluster.ICluster` (3 méthodes)

| # | Méthode |
|---|---|
| 1 | `void setBluetoothContacts(List<BluetoothVCardBook> contacts)` |
| 2 | `void registerCallback(IClusterControlCallback callback)` |
| 3 | `void unregisterCallback(IClusterControlCallback callback)` |

## `com.jancar.services.cluster.IClusterControlCallback` (2 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onPageDown(int appType)` |
| 2 | `void onPageUp(int appType)` |

## `com.jancar.services.dab.IDAB` (6 méthodes)

| # | Méthode |
|---|---|
| 1 | `void open(IDABCallback callback)` |
| 2 | `void close()` |
| 3 | `String getMiddlewareVersion()` |
| 4 | `String getSWVersion()` |
| 5 | `String getHWVersion()` |
| 6 | `void triggerBandScan()` |

## `com.jancar.services.dab.IDABCallback` (1 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onSystemNotification(int systemState)` |

## `com.jancar.services.media.IGetMediaListCallback` (2 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onProgress(List<StMusic> stAudios, String path)` |
| 2 | `void onFinish(List<StMusic> stAudios, String path)` |

## `com.jancar.services.media.IMedia` (33 méthodes)

| # | Méthode |
|---|---|
| 1 | `void open(int mediaType, IMediaControlCallback callback, String packageName)` |
| 2 | `void close(int mediaType)` |
| 3 | `void registerScannerCallback(IMediaScannerCallback callback)` |
| 4 | `void unRegisterScannerCallback(IMediaScannerCallback callback)` |
| 5 | `void registerMediaInfoCallback(IMediaInfoCallback callback)` |
| 6 | `void unRegisterMediaInfoCallback(IMediaInfoCallback callback)` |
| 7 | `int getActiveMedia()` |
| 8 | `void setMediaInfo(int mediaType, String name, String info, int artWidth, int artHeight, byte[] artPixels, int index, int totalCount, boolean popup)` |
| 9 | `void setMediaState(int mediaType, int playState, int position, int duration)` |
| 10 | `void getAllMediaList(String type, String startWidtPath, IGetMediaListCallback callback)` |
| 11 | `void getAppointPathMediaList(String type, String path, IGetMediaListCallback callback)` |
| 12 | `boolean isVideoPermit()` |
| 13 | `void unRegisterMediaControlCallback(IMediaControlCallback callback)` |
| 14 | `boolean canPlayMedia()` |
| 15 | `void play()` |
| 16 | `void pause()` |
| 17 | `void playPause()` |
| 18 | `void prev()` |
| 19 | `void next()` |
| 20 | `void seekTo(int msec)` |
| 21 | `void launchApp(int mediaType)` |
| 22 | `void requestMediaInfoAndStateEvent()` |
| 23 | `void openMediaInZone(int mediaType, IMediaControlCallback callback, String packageName, int zone)` |
| 24 | `int getMediaFromZone(int zone)` |
| 25 | `void setMediaZone(int mediaType, int zone)` |
| 26 | `int getMediaZone(int mediaType)` |
| 27 | `void switchMediaZone(int mediaType, int targetZone, boolean enable)` |
| 28 | `boolean isOpened(int mediaType)` |
| 29 | `void sendDeleteFileEvent(String path)` |
| 30 | `void sendWriteFinishedEvent(String path)` |
| 31 | `void registerMusicControlCallback(IMusicControlCallback callback)` |
| 32 | `void unregisterMusicControlCallback(IMusicControlCallback callback)` |
| 33 | `void setCurrentShownMediaType(int meidaType)` |

## `com.jancar.services.media.IMediaControlCallback` (18 méthodes)

| # | Méthode |
|---|---|
| 1 | `void suspend()` |
| 2 | `void resume()` |
| 3 | `void pause()` |
| 4 | `void play()` |
| 5 | `void playPause()` |
| 6 | `void quitApp(int quitSource)` |
| 7 | `void stop()` |
| 8 | `void setVolume(float volume)` |
| 9 | `void next()` |
| 10 | `void prev()` |
| 11 | `void select(int index)` |
| 12 | `void setFavour(boolean isFavour)` |
| 13 | `void onVideoPermitChanged(boolean show)` |
| 14 | `void seekTo(int msec)` |
| 15 | `void setPlayMode(int mode)` |
| 16 | `void playRandom()` |
| 17 | `void filter(String title, String singer)` |
| 18 | `void setFrequencyDoubling(int operation, int rate)` |

## `com.jancar.services.media.IMediaInfoCallback` (4 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onMediaChange(int mediaType, String name, String info, int artWidth, int artHeight, byte[] artPixels, int index, int totalCount, boolean popup)` |
| 2 | `void onPlayStateChange(int mediaType, int playState, int position, int duration)` |
| 3 | `void onMediaZoneChanged(int mediaType, int zone)` |
| 4 | `void onCurrentShownMediaTypeChanged(int mediaType)` |

## `com.jancar.services.media.IMediaScannerCallback` (4 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onScanStart(int type, int sqlType)` |
| 2 | `void onScanFinish(int type, int sqlType, String path, String oldPath)` |
| 3 | `void onEject(String path, boolean isDiskPowerDown)` |
| 4 | `void onMount(String path)` |

## `com.jancar.services.media.IMusicControlCallback` (3 méthodes)

| # | Méthode |
|---|---|
| 1 | `void setPlayMode(int mode)` |
| 2 | `void playRandom()` |
| 3 | `void filter(String title, String singer)` |

## `com.jancar.services.navigation.INavigation` (2 méthodes)

| # | Méthode |
|---|---|
| 1 | `void registerNavigationCallback(INavigationCallback callback, String packageName)` |
| 2 | `void unRegisterNavigationCallback(INavigationCallback callback)` |

## `com.jancar.services.navigation.INavigationCallback` (4 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onNavigationType(int twelveClock, int turnID, int[] arrayTurn, int guideType, int distance, int destDistance, int destTime, String roadName, String nextRoadName, String destName)` |
| 2 | `void onNavigationAddress(String province, String city, String county)` |
| 3 | `void onNavigationGuide(int direction)` |
| 4 | `void onNavigationEyeInfo(int type, int distance, int speedLimit)` |

## `com.jancar.services.radio.IRadio` (37 méthodes)

| # | Méthode |
|---|---|
| 1 | `void open(IRadioCallback callback, String packageName)` |
| 2 | `void close()` |
| 3 | `void setFreq(int freq)` |
| 4 | `int getFreq()` |
| 5 | `void setBand(int band)` |
| 6 | `int getBand()` |
| 7 | `void setLocation(int location)` |
| 8 | `void scanUp(int freqStart)` |
| 9 | `void scanDown(int freqStart)` |
| 10 | `void scanAll()` |
| 11 | `boolean scanStop()` |
| 12 | `void step(int direction)` |
| 13 | `int getId()` |
| 14 | `void selectRdsTa(boolean on)` |
| 15 | `void selectRdsAf(boolean on)` |
| 16 | `void selectRdsPty(int pty)` |
| 17 | `void selectRdsTp(boolean on)` |
| 18 | `void setStationDisplayName(String name, boolean popup)` |
| 19 | `void setZone(int zone)` |
| 20 | `int getZone()` |
| 21 | `void openInZone(IRadioCallback callback, String packageName, int zone)` |
| 22 | `String getPSText(int freq)` |
| 23 | `int getScanAction()` |
| 24 | `void setFMScanCondition(float usn, float wam, float offset, float bw, float autoLevel, float manualLevel)` |
| 25 | `float[] getFMScanConditions()` |
| 26 | `void setAMScanCondition(float offset, float bw, float autoLevel, float manualLevel)` |
| 27 | `float[] getAMScanConditions()` |
| 28 | `void mute()` |
| 29 | `void unMute()` |
| 30 | `int getfreqValid(int freq)` |
| 31 | `void setStereo(boolean isStereo)` |
| 32 | `boolean isStereo()` |
| 33 | `void setDistanceMode(boolean isDistance)` |
| 34 | `boolean getDistanceMode()` |
| 35 | `void send(int cmd, int[] ints, float[] flts, String[] strs)` |
| 36 | `int[] getI(int cmd)` |
| 37 | `String[] getS(int cmd)` |

## `com.jancar.services.radio.IRadioCallback` (30 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onSetFreq(int freq)` |
| 2 | `void onPowerOn()` |
| 3 | `void onPowerOff()` |
| 4 | `void onFreqChanged(int freq)` |
| 5 | `void onScanResult(int freq, int signalStrength)` |
| 6 | `void onScanStart(boolean isScanAll)` |
| 7 | `void onScanEnd(boolean isScanAll)` |
| 8 | `void onScanAbort(boolean isScanAll)` |
| 9 | `void onSignalUpdate(int freq, int signalStrength)` |
| 10 | `void suspend()` |
| 11 | `void resume()` |
| 12 | `void pause()` |
| 13 | `void play()` |
| 14 | `void playPause()` |
| 15 | `void stop()` |
| 16 | `void next()` |
| 17 | `void prev()` |
| 18 | `void quitApp()` |
| 19 | `void select(int index)` |
| 20 | `void setFavour(boolean isFavour)` |
| 21 | `void onRdsPsChanged(int pi, int freq, String ps)` |
| 22 | `void onRdsRtChanged(int pi, int freq, String rt)` |
| 23 | `void onRdsMaskChanged(int pi, int freq, int pty, int tp, int ta)` |
| 24 | `void onTuneRotate(boolean add)` |
| 25 | `void scanUp()` |
| 26 | `void scanDown()` |
| 27 | `void scanAll()` |
| 28 | `void setNumberkey(int key)` |
| 29 | `void onStereo(int freq, boolean bStereo)` |
| 30 | `void onCMDServiceToApp(int cmd, int[] ints, float[] flts, String[] strs)` |

## `com.jancar.services.system.IGpsCallback` (2 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onGpsLocationInfoChanged(String longitude, String latitude, float accuracy, double altitude, float fSpeed)` |
| 2 | `void onGpsCountChanged(int iGpsInView, int iGpsInUse, int iGlonassInView, int iGLonassInUse)` |

## `com.jancar.services.system.ISystem` (38 méthodes)

| # | Méthode |
|---|---|
| 1 | `void setScreenBrightness(int id, int brightness)` |
| 2 | `int getScreenBrightness(int id)` |
| 3 | `int getMaxScreenBrightness()` |
| 4 | `int getCurrentBrightnessId()` |
| 5 | `void registerSystemCallback(ISystemCallback callback, String packageName)` |
| 6 | `void unRegisterSystemCallback(ISystemCallback callback)` |
| 7 | `void startPrintLogcat(String filePath)` |
| 8 | `void stopPrintLogcat()` |
| 9 | `void reboot()` |
| 10 | `void closeBackLight(int powerKeyAction)` |
| 11 | `void openBackLight()` |
| 12 | `void openBackLightUnitOn()` |
| 13 | `boolean isOpenBackLight()` |
| 14 | `void recoverySystem(boolean isClearINand)` |
| 15 | `void closeApp(String packageName)` |
| 16 | `void closeAllApp()` |
| 17 | `List<String> getNotRunActivityPackageNames(List<String> historyPackages)` |
| 18 | `void openMemoryApp()` |
| 19 | `String getMemoryAppPackageName()` |
| 20 | `void openNaviApp()` |
| 21 | `void resetMute()` |
| 22 | `void registerGpsLocationInfoListener(IGpsCallback callback)` |
| 23 | `void unregisterGpsLocationInfoListener(IGpsCallback callback)` |
| 24 | `boolean isInPosition()` |
| 25 | `void setTPTouch(boolean enable)` |
| 26 | `void setTboxOpen(boolean isOpen)` |
| 27 | `boolean isTboxOpen()` |
| 28 | `void setScreenProtectionTime(int time)` |
| 29 | `int getScreenProtectionTime()` |
| 30 | `void setScreenProtectionStatus(boolean isEnterScreenProtection)` |
| 31 | `void requestScreenOperate(int from)` |
| 32 | `void setTelPhoneStatus(int status, String phoneNumber, String phoneName)` |
| 33 | `boolean isScreenLock()` |
| 34 | `void doPowerKeyLockScreen(boolean lock)` |
| 35 | `void setTouchLearnStatus(boolean status)` |
| 36 | `void setExternalPowerAmplifier(boolean open)` |
| 37 | `void onCMDAppToService(int cmd, int[] ints, float[] flts, String[] strs)` |
| 38 | `void CmdApp2Serv(int cmd, int[] ints, int[] datas, String[] strs)` |

## `com.jancar.services.system.ISystemCallback` (14 méthodes)

| # | Méthode |
|---|---|
| 1 | `void onOpenScreen(int from)` |
| 2 | `void onCloseScreen(int from)` |
| 3 | `void onScreenBrightnessChange(int id, int brightness)` |
| 4 | `void onCurrentScreenBrightnessChange(int id, int brightness)` |
| 5 | `void quitApp()` |
| 6 | `void startNavigationApp()` |
| 7 | `void onMediaAppChanged(String packageName, boolean isOpen)` |
| 8 | `void gotoSleep()` |
| 9 | `void wakeUp()` |
| 10 | `void onFloatBarVisibility(int visibility)` |
| 11 | `void onTboxChange(boolean isOpen)` |
| 12 | `void onScreenProtection(boolean isEnterScreenProtection)` |
| 13 | `void onTelPhoneStatusChange(int status, String phoneNumber, String phoneName)` |
| 14 | `void onTouchEventPos(int x, int y)` |

## `com.jancar.services.voice.IVoice` (29 méthodes)

| # | Méthode |
|---|---|
| 1 | `void voiceControlCMD(int id, int value, boolean isOn, String str1, String str2)` |
| 2 | `void selectMediaItem(int index)` |
| 3 | `void playMedia(String title, String singer)` |
| 4 | `void play()` |
| 5 | `void pause()` |
| 6 | `void prev()` |
| 7 | `void next()` |
| 8 | `void favourMedia(boolean isFavour)` |
| 9 | `void setPlayMode(int mode)` |
| 10 | `void playRandom()` |
| 11 | `void incVolume()` |
| 12 | `void decVolume()` |
| 13 | `void setVolume(int volume)` |
| 14 | `void setMute(boolean isMute)` |
| 15 | `void setFreq(int freq)` |
| 16 | `void startScan()` |
| 17 | `void stopScan()` |
| 18 | `void setBand(int band)` |
| 19 | `void openApp(String packageName, String className)` |
| 20 | `void closeApp(String packageName)` |
| 21 | `void openAvmOperation(int type)` |
| 22 | `void openScreen()` |
| 23 | `void closeScreen()` |
| 24 | `void openVoiceApp()` |
| 25 | `void closeVoiceApp()` |
| 26 | `void startVoice()` |
| 27 | `void endVoice()` |
| 28 | `void registerVoiceCallback(IVoiceCallback callback)` |
| 29 | `void unregisterVoiceCallback(IVoiceCallback callback)` |

## `com.jancar.services.voice.IVoiceCallback` (5 méthodes)

| # | Méthode |
|---|---|
| 1 | `void openVoiceApp()` |
| 2 | `void closeVoiceApp()` |
| 3 | `void onAvmOperation(int type)` |
| 4 | `void onAccChange(boolean isAccOn)` |
| 5 | `void onCcdChange(boolean isCcdOn)` |
