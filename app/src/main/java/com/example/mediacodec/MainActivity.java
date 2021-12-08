package com.example.mediacodec;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "MediaCodec";
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" };

    private static final String SD_PATH = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getPath();
    // aac文件（初始文件）
    private static final String AAC_PATH = SD_PATH + "/input.aac";
    // pcm文件
    private static final String PCM_PATH = SD_PATH + "/input.pcm";
    // aac文件（结果文件）
    private static final String AAC_RESULT_PATH = SD_PATH + "/out.aac";
    private static final String PCM_RESULT_PATH = SD_PATH + "/input1.pcm";

    private static final String PREFIX_AUDIO = "audio/";

    private AudioDecodeTask mAudioDecodeTask;
    private AudioEncodeTask mAudioEncodeTask;
    private PlayInModeStreamTask mPlayTask;
    private AudioTrack mAudioTrack;

    public interface CodecListener {
        void codecFinish();
        void codecFail();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        verifyStoragePermissions();
        Log.d(TAG, "SD_PATH: " + SD_PATH);
        addOnClickListener(R.id.btn_decode_audio, R.id.btn_play_pcm,
                R.id.btn_encode_audio, R.id.btn_play_aac);

    }

    private void addOnClickListener(int... ids) {
        for (int id : ids) {
            Button button = findViewById(id);
            button.setOnClickListener(this);
        }
    }

    private void verifyStoragePermissions() {
        //检测是否有写的权限
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            //没有写的权限，去申请写的权限，会弹出对话框
            ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_EXTERNAL_STORAGE) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, permissions[i] + "权限被禁止");
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlay();
        if (mAudioDecodeTask != null) {
            mAudioDecodeTask.cancel(true);
            mAudioDecodeTask = null;
        }
        if (mAudioEncodeTask != null) {
            mAudioEncodeTask.cancel(true);
            mAudioEncodeTask = null;
        }
    }

    //首先将aac解码成PCM，再将PCM编码成aac格式的音频文件
    @Override
    public void onClick(View v) {
        Log.d(TAG, "onClick: " + v.getId());
        switch (v.getId()) {
            case R.id.btn_decode_audio:
                decode(AAC_PATH, PCM_PATH,new CodecListener() {
                    @Override
                    public void codecFinish() {
                        decodeEnd(true);
                    }

                    @Override
                    public void codecFail() {
                        decodeEnd(false);
                    }
                });
                break;
            case R.id.btn_play_pcm:
                Button btnPlay = (Button) v;
                if (btnPlay.getText().toString().equals(getString(R.string.play_pcm))) {
                    btnPlay.setText(getString(R.string.stop_play));
                    // 播放pcm
                    playInModeStream(PCM_PATH);
                } else {
                    stopPlayInModeStream();
                }
                break;
            case R.id.btn_encode_audio:
                encode();
                break;
            case R.id.btn_play_aac:
                decode(AAC_RESULT_PATH, PCM_RESULT_PATH, new CodecListener() {
                    @Override
                    public void codecFinish() {
                        decodeEnd(true);
                        playInModeStream(PCM_RESULT_PATH);
                    }

                    @Override
                    public void codecFail() {
                        decodeEnd(false);
                    }
                });
            default:
                break;
        }
    }

    private void decode(String audioPath, String outPath, CodecListener listener) {
        if (mAudioDecodeTask != null) {
            Log.w(TAG, getString(R.string.running));
            return;
        }
        //此类可分离视频文件的音轨和视频轨道
        MediaExtractor extractor = new MediaExtractor();
        //音频MP3文件其实只有一个音轨
        int trackIndex = -1;
        //判断音频文件是否有音频音轨
        boolean hasAudio = false;

        try {
            extractor.setDataSource(audioPath);
            int trackCount = extractor.getTrackCount();
            Log.d(TAG, "trackCount：" + trackCount);
            for (int i = 0; i < trackCount; i++) {
                if (extractor.getTrackFormat(i).
                        getString(MediaFormat.KEY_MIME).startsWith(PREFIX_AUDIO)) {
                    trackIndex = i;
                    hasAudio = true;
                    break;
                }
            }

            Log.d(TAG, "hasAudio：" + hasAudio);
            if (hasAudio) {
                extractor.selectTrack(trackIndex);
                mAudioDecodeTask = new AudioDecodeTask(extractor, trackIndex, outPath, listener);
                mAudioDecodeTask.execute();
            } else {
                notifyDecodeFail(listener);
            }
        } catch (IOException e) {
            e.printStackTrace();
            notifyDecodeFail(listener);
        }
    }

    private void decodeEnd(boolean success){
        showToast(success ? R.string.audio_decode_finish : R.string.audio_decode_fail);
        mAudioDecodeTask = null;
    }

    private void notifyDecodeFail(CodecListener listener) {
        if (listener != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG,"解码失败");
                    listener.codecFail();
                }
            });
        }
    }

    private void encode() {
        if (mAudioEncodeTask != null) {
            Log.w(TAG, getString(R.string.running));
            return;
        }
        mAudioEncodeTask = new AudioEncodeTask(PCM_PATH, AAC_RESULT_PATH, new CodecListener() {
            @Override
            public void codecFinish() {
                encodeEnd(true);
            }
            @Override
            public void codecFail() {
                encodeEnd(false);
            }
        });
        mAudioEncodeTask.execute();
    }

    private void encodeEnd(boolean success) {
        showToast(success ? R.string.audio_encode_finish : R.string.audio_encode_fail);
        mAudioEncodeTask = null;
    }

    private void playInModeStream(String path) {
        mPlayTask = new PlayInModeStreamTask(this, path);
        mPlayTask.execute();
    }

    private void stopPlayInModeStream() {
        stopPlay(R.id.btn_play_pcm, R.string.play_pcm);
    }

    private void stopAACPlay() {
        stopPlay(R.id.btn_play_aac, R.string.play_aac);
    }

    private void stopPlay(int id, int stringId) {
        Button button = findViewById(id);
        button.setText(getString(stringId));
        stopPlay();
    }

    private void stopPlay() {
        if (mPlayTask != null) {
            mPlayTask.cancel(true);
            mPlayTask = null;
        }
        if (mAudioTrack != null) {
            Log.d(TAG, "Stopping");
            mAudioTrack.stop();
            Log.d(TAG, "Releasing");
            mAudioTrack.release();
            Log.d(TAG, "Nulling");
            mAudioTrack = null;
        }
    }

    private void showToast(int id) {
        Toast.makeText(MainActivity.this, id, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "showToast##################" + getString(id));
    }

    private static class PlayInModeStreamTask extends AsyncTask<Void, Void, Void> {
        /**
         * 采样率，现在能够保证在所有设备上使用的采样率是44100Hz, 但是其他的采样率（22050, 16000, 11025）在一些设备上也可以使用。
         */
        public static final int SAMPLE_RATE_INHZ = 44100;
        /**
         * 声道数。CHANNEL_IN_MONO (单通道) and CHANNEL_IN_STEREO（双通道）. 其中CHANNEL_IN_MONO是可以保证在所有设备能够使用的。
         */
        public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
        /**
         * 返回的音频数据的格式。 ENCODING_PCM_8BIT, ENCODING_PCM_16BIT, and ENCODING_PCM_FLOAT.
         */
        public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        WeakReference<MainActivity> activity;
        String pcmPath;
        PlayInModeStreamTask(MainActivity activity, String path) {
            this.activity = new WeakReference<>(activity);
            this.pcmPath = path;
        }

        @Override
        protected void onPreExecute() {
            if (activity == null || activity.get() == null) {
                return;
            }
            /*
             * SAMPLE_RATE_INHZ 对应pcm音频的采样率
             * channelConfig 对应pcm音频的声道
             * AUDIO_FORMAT 对应pcm音频的格式
             * */
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_INHZ, channelConfig, AUDIO_FORMAT);
            activity.get().mAudioTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE_INHZ)
                            .setEncoding(AUDIO_FORMAT)
                            .setChannelMask(channelConfig)
                            .build(),
                    minBufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            activity.get().mAudioTrack.play();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            if (activity == null || activity.get() == null) {
                return null;
            }
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_INHZ, channelConfig, AUDIO_FORMAT);
            File file = new File(pcmPath);
            Log.d(TAG, "playInModeStream: " + file);
            try {
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[minBufferSize];
                    while (!isCancelled() && in.available() > 0) {
                        int readCount = in.read(buffer);
                        if (readCount == AudioTrack.ERROR_INVALID_OPERATION || readCount == AudioTrack.ERROR_BAD_VALUE) {
                            continue;
                        }
                        if (readCount != 0 && readCount != -1 && activity.get().mAudioTrack != null) {
                            activity.get().mAudioTrack.write(buffer, 0, readCount);
                        }
                    }
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            if (activity == null || activity.get() == null) {
                return;
            }
            if (pcmPath.equals(PCM_PATH)) {
                activity.get().stopPlayInModeStream();
            } else {
                activity.get().stopAACPlay();
            }
        }
    }

    private static class AudioDecodeTask extends AsyncTask<Void, Void, Boolean> {
        private final static int TIMEOUT = 0;
        private MediaExtractor mExtractor;
        private int mTrackIndex;
        private String mPcmPath;
        private CodecListener mListener;

        public AudioDecodeTask(MediaExtractor extractor, int trackIndex,
                               String outPath, CodecListener listener) {
            this.mExtractor = extractor;
            this.mTrackIndex = trackIndex;
            this.mPcmPath = outPath;
            this.mListener = listener;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                //直接从MP3音频文件中得到音轨的MediaFormat
                MediaFormat format = mExtractor.getTrackFormat(mTrackIndex);
                //初始化音频解码器,并配置解码器属性
                MediaCodec mediaCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                mediaCodec.configure(format, null, null, 0);

                //启动MediaCodec，等待传入数据
                mediaCodec.start();

                //获取需要编码数据的输入流队列，返回的是一个ByteBuffer数组
                ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
                //获取编解码之后的数据输出流队列，返回的是一个ByteBuffer数组
                ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();

                //用于描述解码得到的byte[]数据的相关信息
                MediaCodec.BufferInfo decodeInfo = new MediaCodec.BufferInfo();
                //用于描述输入数据的byte[]数据的相关信息
                MediaCodec.BufferInfo inputInfo = new MediaCodec.BufferInfo();
                boolean finish = false;
                //整体输入结束标记
                boolean inputDone = false;

                FileOutputStream fos = new FileOutputStream(mPcmPath);
                while (!finish && !isCancelled()) {
                    if (!inputDone) {
                        for (int i = 0; i < inputBuffers.length; i++) {
                            //从输入流队列中取数据进行操作
                            //返回用于填充有效数据的输入buffer的索引，如果当前没有可用的buffer，则返回-1
                            int inputIndex = mediaCodec.dequeueInputBuffer(TIMEOUT);
                            if (inputIndex >= 0) {
                                //从分离器拿出输入，写入解码器
                                //拿到inputBuffer
                                ByteBuffer inputBuffer = inputBuffers[inputIndex];
                                //将position置为0，并不清除buffer内容
                                inputBuffer.clear();

                                //将MediaExtractor读取数据到inputBuffer
                                int sampleSize = mExtractor.readSampleData(inputBuffer, 0);
                                if (sampleSize < 0) { //表示所有数据已经读取完毕
                                    inputDone = true;
                                    mediaCodec.queueInputBuffer(inputIndex, 0, 0, 0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                } else {
                                    inputInfo.offset = 0;
                                    inputInfo.size = sampleSize;
                                    inputInfo.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                                    inputInfo.presentationTimeUs = mExtractor.getSampleTime();

                                    //Log.d(TAG, "往解码器写入数据，当前时间戳：" + inputInfo.presentationTimeUs);
                                    //通知MediaCodec解码刚刚传入的数据
                                    mediaCodec.queueInputBuffer(inputIndex, inputInfo.offset, sampleSize,
                                            inputInfo.presentationTimeUs, 0);
                                    //读取下一帧数据
                                    mExtractor.advance();
                                }
                            }
                        }
                    }

                    /* dequeueInputBuffer dequeueOutputBuffer 返回值解释
                    INFO_TRY_AGAIN_LATER=-1 等待超时
                    INFO_OUTPUT_FORMAT_CHANGED=-2 媒体格式更改
                    INFO_OUTPUT_BUFFERS_CHANGED=-3 缓冲区已更改（过时）
                    大于等于0的为缓冲区数据下标
                     */

                    //整体解码结束标记
                    boolean decodeOutputDone = false;
                    byte[] pcmData;
                    while (!decodeOutputDone) {
                        int outputIndex = mediaCodec.dequeueOutputBuffer(decodeInfo, TIMEOUT);
                        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // 没有可用的解码器
                            decodeOutputDone = true;
                        } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            outputBuffers = mediaCodec.getOutputBuffers();
                        } else if (outputIndex >= 0) {
                            ByteBuffer outputBuffer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ?
                                    mediaCodec.getOutputBuffer(outputIndex) : outputBuffers[outputIndex];

                            pcmData = new byte[decodeInfo.size];
                            outputBuffer.get(pcmData);
                            outputBuffer.clear();

                            //数据写入文件中
                            fos.write(pcmData);
                            fos.flush();
                            //Log.d(TAG,"释放输出流缓冲区：" + outputIndex);
                            mediaCodec.releaseOutputBuffer(outputIndex, false);

                            if ((decodeInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                mExtractor.release();
                                mediaCodec.stop();
                                mediaCodec.release();
                                finish = true;
                                decodeOutputDone = true;
                            }

                        }
                    }
                }
                fos.close();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (mListener == null || isCancelled()) {
                return;
            }
            if (success) {
                mListener.codecFinish();
            } else {
                mListener.codecFail();
            }
        }
    }

    private static class AudioEncodeTask extends AsyncTask<Void, Void, Boolean> {
        private static final int BUFFER_SIZE = 100 * 1024;
        private static final int SAMPLE_RATE = 44100;
        private static final int CHANNEL_COUNT = 2;
        private static final int BIT_RATE = 96000;
        private static final int MAX_INPUT_SIZE = 500 * 1024;
        private static final long TIME_OUT = 10000;
        private static final int ADTS_HEAD_SIZE = 7;

        private String mPcmPath;
        private String mAudioPath;
        private CodecListener mListener;

        public AudioEncodeTask(String pcmPath, String audioPath, CodecListener listener) {
            this.mPcmPath = pcmPath;
            this.mAudioPath = audioPath;
            this.mListener = listener;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (new File(mPcmPath).exists() && !isCancelled()) {
                try {
                    FileInputStream fis = new FileInputStream(mPcmPath);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    byte[] data;

                    int inputIndex, outputIndex;
                    ByteBuffer inputBuffer, outputBuffer;

                    byte[] targetData;
                    int outBitSize;
                    int outPacketSize;

                    //初始化编码格式 mimetype 采样率 声道数
                    MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                            SAMPLE_RATE, CHANNEL_COUNT);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);

                    //初始化编码器
                    MediaCodec mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    mediaCodec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                    mediaCodec.start();

                    ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
                    ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                    //初始化文件写入流
                    FileOutputStream fos = new FileOutputStream(mAudioPath);
                    BufferedOutputStream bos = new BufferedOutputStream(fos, MAX_INPUT_SIZE);
                    boolean readFinish = false;
                    while (!readFinish && !isCancelled()) {
                        //减掉1很重要，不要忘记
                        for (int i = 0; i < inputBuffers.length - 1; i++) {
                            if (fis.read(buffer) != -1) {
                                data = Arrays.copyOf(buffer, buffer.length);
                            } else {
                                Log.d(TAG,"文件读取完成");
                                readFinish = true;
                                break;
                            }
                            //Log.d(TAG,"读取文件并写入编码器" + data.length);

                            //从输入流队列中取数据进行编码操作
                            //dequeueInputBuffer（time）需要传入一个时间值，-1表示一直等待，0表示不等待有可能会丢帧，其他表示等待多少毫秒
                            inputIndex = mediaCodec.dequeueInputBuffer(-1);
                            if (inputIndex >= 0) {
                                inputBuffer = inputBuffers[inputIndex];
                                inputBuffer.clear();
                                inputBuffer.limit(data.length);//限制ByteBuffer的访问长度
                                //将pcm数据填充给inputBuffer
                                inputBuffer.put(data);//添加数据
                                //在指定索引处填充输入buffer后，使用queueInputBuffer将buffer提交给组件
                                mediaCodec.queueInputBuffer(inputIndex, 0, data.length, 0, 0);
                            }
                        }

                        outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIME_OUT);
                        while (outputIndex >= 0) {
                            //从解码器中取出数据
                            outBitSize = bufferInfo.size;
                            //添加ADTS头部后的长度，7为adts头部大小
                            outPacketSize = outBitSize + ADTS_HEAD_SIZE;
                            //拿到输出的buffer
                            outputBuffer = outputBuffers[outputIndex];
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + outBitSize);

                            targetData = new byte[outPacketSize];
                            //添加ADTS
                            addADTStoPacket(targetData, outPacketSize);
                            //将编码得到的AAC数据取出到byte[]中，偏移量为7
                            outputBuffer.get(targetData, ADTS_HEAD_SIZE, outBitSize);
                            outputBuffer.position(bufferInfo.offset);
                            //Log.d(TAG, "编码成功并写入文件" + targetData.length);

                            //将文件保存在sdcard中
                            bos.write(targetData, 0, targetData.length);
                            bos.flush();

                            mediaCodec.releaseOutputBuffer(outputIndex, false);
                            outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIME_OUT);
                        }
                    }
                    mediaCodec.stop();
                    mediaCodec.release();
                    fos.close();
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }

        /**
         * 写入ADTS头部数据
         * @param packet
         * @param packetLen
         */
        private static void addADTStoPacket(byte[] packet, int packetLen) {
            int profile = 2; // AAC LC
            int freqIdx = 4; // 44.1KHz
            int chanCfg = 2; // CPE

            packet[0] = (byte) 0xFF;
            packet[1] = (byte) 0xF9;
            packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
            packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
            packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
            packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
            packet[6] = (byte) 0xFC;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (mListener == null || isCancelled()) {
                return;
            }
            if (success) {
                mListener.codecFinish();
            } else {
                mListener.codecFail();
            }
        }
    }
}