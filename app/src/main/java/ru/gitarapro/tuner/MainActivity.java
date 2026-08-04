package ru.gitarapro.tuner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private TunerView view;
    private PitchEngine engine;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(8,8,10));
        w.setNavigationBarColor(Color.rgb(8,8,10));
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new TunerView(this);
        setContentView(view);
    }

    void beginListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        stopListening();
        engine = new PitchEngine((hz,db,clarity) -> runOnUiThread(() -> view.onAudioSample(hz,db,clarity)));
        engine.start();
    }

    void stopListening() { if (engine != null) { engine.shutdown(); engine = null; } }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == 7 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) beginListening();
        else view.permissionDenied = true;
    }
    @Override protected void onPause() { super.onPause(); stopListening(); }
    @Override protected void onResume() { super.onResume(); if (view != null && view.screen == 1) beginListening(); }

    void playReference(double hz) { SoundEngine.reference(hz); }
    void playSuccessSound() { SoundEngine.success(); }

    static class SoundEngine {
        static final int RATE=44100;
        static void reference(double hz) { new Thread(() -> {
            int count=(int)(RATE*1.28), attack=(int)(RATE*.022); short[] pcm=new short[count];
            for(int i=0;i<count;i++){
                double t=i/(double)RATE, fadeOut=Math.min(1,(1.28-t)/.16), env=Math.min(1,i/(double)attack)*Math.max(0,fadeOut)*(.72+.28*Math.exp(-2.2*t));
                double shimmer=.025*Math.sin(2*Math.PI*(hz*2.002)*t)*Math.exp(-2.7*t);
                double wave=.86*Math.sin(2*Math.PI*hz*t)+.10*Math.sin(4*Math.PI*hz*t)+.035*Math.sin(6*Math.PI*hz*t)+shimmer;
                pcm[i]=(short)(Math.max(-1,Math.min(1,wave*env))*28500);
            }
            play(pcm);
        },"Reference tone").start(); }
        static void success() { new Thread(() -> {
            int count=(int)(RATE*.58); short[] pcm=new short[count];
            for(int i=0;i<count;i++){
                double t=i/(double)RATE, wave=0;
                if(t<.28){double u=t; wave=Math.sin(2*Math.PI*880*u)*Math.exp(-8*u);}
                if(t>.15){double u=t-.15; wave+=.82*Math.sin(2*Math.PI*1318.51*u)*Math.exp(-7*u);}
                pcm[i]=(short)(wave*18000);
            }
            play(pcm);
        },"Success chime").start(); }
        static void play(short[] pcm){
            AudioTrack track=null;
            try{
                track=new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.length*2).setTransferMode(AudioTrack.MODE_STATIC).build();
                track.write(pcm,0,pcm.length);track.play();Thread.sleep((long)(pcm.length*1000.0/RATE)+80);
            }catch(Exception ignored){}finally{if(track!=null){try{track.stop();track.release();}catch(Exception ignored){}}}
        }
    }

    interface PitchListener { void sample(double hz,double db,double clarity); }

    static class PitchEngine extends Thread {
        private final PitchListener listener;
        private volatile boolean running = true;
        private AudioRecord recorder;
        private double lastDb=-90,lastClarity=0;
        PitchEngine(PitchListener l) { listener = l; setName("Pitch detector"); }
        void shutdown() { running = false; if (recorder != null) try { recorder.stop(); } catch(Exception ignored) {} interrupt(); }

        @SuppressLint("MissingPermission")
        @Override public void run() {
            final int rate = 44100, n = 4096;
            int min = Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), n * 2);
            try {
                try {
                    if (android.os.Build.VERSION.SDK_INT < 24) throw new IllegalArgumentException();
                    recorder = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min);
                } catch (RuntimeException unsupported) {
                    recorder = null;
                }
                if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    if (recorder != null) recorder.release();
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min);
                }
                short[] input = new short[n];
                double[] recent = new double[5]; int recentCount=0,recentPos=0,invalidCount=0;
                recorder.startRecording();
                while (running) {
                    int got = recorder.read(input, 0, n);
                    if (got == n) {
                        double hz = detect(input, rate);
                        double output=-1;
                        if(hz>=60&&hz<=520){
                            if(recentCount>0){double previous=recent[(recentPos+4)%5];if(Math.abs(Math.log(hz/previous)/Math.log(2))>.08){recentCount=0;recentPos=0;}}
                            recent[recentPos]=hz;recentPos=(recentPos+1)%5;recentCount=Math.min(5,recentCount+1);invalidCount=0;
                            if(recentCount>=3){
                                double[] sorted=Arrays.copyOf(recent,recentCount);Arrays.sort(sorted);double median=sorted[recentCount/2],spread=0;
                                for(double value:sorted)spread+=Math.abs(value-median);spread/=recentCount;
                                output=spread/median<.018?median:-1;
                            }
                        }else{
                            invalidCount++;if(invalidCount>=2){recentCount=0;recentPos=0;}
                        }
                        listener.sample(output,lastDb,lastClarity);
                    }
                }
            } catch(Exception ignored) {
            } finally {
                if (recorder != null) { try { recorder.release(); } catch(Exception ignored) {} recorder = null; }
            }
        }

        // YIN difference function: reliable for the guitar's low E while remaining lightweight.
        private double detect(short[] x, int rate) {
            int maxTau = Math.min(x.length / 2, rate / 60), minTau = rate / 520;
            double rms = 0, mean = 0;
            for (short v : x) mean += v;
            mean /= x.length;
            for (short v : x) { double d=v-mean; rms += d*d; }
            double rmsValue=Math.sqrt(rms/x.length);lastDb=20*Math.log10(Math.max(1,rmsValue)/32768.0);lastClarity=0;
            if (rmsValue < 300) return -1;
            double[] diff = new double[maxTau + 1];
            for (int tau=1; tau<=maxTau; tau++) {
                double sum=0;
                for (int i=0; i<x.length-maxTau; i++) { double d=x[i]-x[i+tau]; sum+=d*d; }
                diff[tau]=sum;
            }
            double runningSum=0; diff[0]=1;
            for (int tau=1; tau<=maxTau; tau++) { runningSum+=diff[tau]; diff[tau]=runningSum==0?1:diff[tau]*tau/runningSum; }
            int tau=-1;
            for (int t=minTau; t<maxTau; t++) if (diff[t]<0.12) { while(t+1<maxTau && diff[t+1]<diff[t]) t++; tau=t; break; }
            if (tau < 0) return -1;
            double xy=0,xx=0,yy=0;
            for(int i=0;i<x.length-maxTau;i++){double a=x[i]-mean,b=x[i+tau]-mean;xy+=a*b;xx+=a*a;yy+=b*b;}
            lastClarity=xx==0||yy==0?0:Math.max(0,xy/Math.sqrt(xx*yy));if(lastClarity<.78)return -1;
            double better=tau;
            if (tau>1 && tau<maxTau) {
                double a=diff[tau-1], b=diff[tau], c=diff[tau+1], den=2*(2*b-a-c);
                if (Math.abs(den)>1e-9) better += (c-a)/den;
            }
            return rate/better;
        }
    }

    class TunerView extends View {
        final Paint p = new Paint(3);
        final int BG=Color.rgb(8,8,10), CARD=Color.rgb(30,30,36), IVORY=Color.rgb(244,238,220), LIME=Color.rgb(201,255,53), CORAL=Color.rgb(255,92,122), SKY=Color.rgb(116,202,255), MUTED=Color.rgb(145,145,155);
        final String[][] notes={{"E","B","G","D","A","E"},{"A","E","C","G"}};
        final String[][] octaves={{"4","3","3","3","2","2"},{"4","4","4","4"}};
        // 12-TET, concert pitch A4 = 440 Hz. Ukulele uses standard re-entrant high-G tuning.
        final double[][] targets={{329.627557,246.941651,195.997718,146.832384,110.000000,82.406889},
                                  {440.000000,329.627557,261.625565,391.995436}};
        final String[] praise={"COOL!","WOW!","SUPER!","YES!","ИМЕННО!","ОГОНЬ!"};
        int screen=0, mode=0, stringIndex=0, praiseIndex=0,earIndex=0;
        double pitch=-1, cents=0, targetCents=0, needleCents=0,inputDb=-90,inputClarity=0;
        boolean permissionDenied=false, advancing=false, autoMode=true,stageMode=false;
        final boolean[][] tuned={{false,false,false,false,false,false},{false,false,false,false}};
        long screenStarted=System.currentTimeMillis(), praiseUntil=0, praiseStarted=0, stableSince=0, muteUntil=0, lastFrame=0, candidateSince=0,tonePlayingUntil=0;
        int candidateString=-1,topInset=0,bottomInset=0;
        final ArrayList<Particle> particles = new ArrayList<>();
        final Random random = new Random();
        String lastTune;

        @SuppressWarnings("deprecation") TunerView(Context c) {
            super(c); setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            lastTune=getPreferences(0).getString("last","10 мая");
            p.setTypeface(Typeface.create("sans",Typeface.NORMAL));
            setOnApplyWindowInsetsListener((v,insets)->{topInset=insets.getSystemWindowInsetTop();bottomInset=insets.getSystemWindowInsetBottom();invalidate();return insets;});
        }
        float d(float v){ return v*getResources().getDisplayMetrics().density; }
        float contentHeight(){return Math.max(1,getHeight()-topInset-bottomInset);}
        void text(Canvas c,String s,float size,int color,float x,float y,Paint.Align align,boolean bold){
            p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(d(size));p.setTextAlign(align);
            p.setTypeface(Typeface.create(bold?"sans-serif-condensed":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL)); c.drawText(s,x,y,p);
        }
        void displayText(Canvas c,String s,float size,int color,float x,float y,Paint.Align align){
            p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(d(size));p.setTextAlign(align);
            p.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD));c.drawText(s,x,y,p);
        }
        void round(Canvas c,float l,float t,float r,float b,float rad,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRoundRect(l,t,r,b,d(rad),d(rad),p);}

        @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(BG);c.save();c.translate(0,topInset);if(screen==0)drawHome(c);else if(screen==1){if(stageMode)drawStage(c);else drawTuner(c);}else if(screen==2)drawFinish(c);else drawEar(c);c.restore();}

        void drawBrand(Canvas c){
            round(c,d(20),d(15),d(58),d(43),d(14),CORAL);displayText(c,"В",15,BG,d(39),d(35),Paint.Align.CENTER);
            displayText(c,"ТОН",17,IVORY,d(66),d(36),Paint.Align.LEFT);
        }
        void drawBack(Canvas c){
            round(c,d(17),d(14),d(67),d(46),d(16),CARD);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(d(2.4f));p.setColor(IVORY);
            Path arrow=new Path();arrow.moveTo(d(43),d(23));arrow.lineTo(d(34),d(30));arrow.lineTo(d(43),d(37));c.drawPath(arrow,p);
        }
        void drawHome(Canvas c){
            drawBrand(c); float w=getWidth(),h=contentHeight();
            displayText(c,"ПОПАДИ",50,IVORY,d(24),h*.25f,Paint.Align.LEFT);
            displayText(c,"В ТОН.",54,CORAL,d(24),h*.25f+d(55),Paint.Align.LEFT);
            text(c,"Точный строй без лишнего шума",16,MUTED,d(25),h*.25f+d(94),Paint.Align.LEFT,false);
            // Mode segmented control
            round(c,d(20),h*.46f,w-d(20),h*.46f+d(62),d(31),CARD);
            float mid=w/2; round(c,mode==0?d(24):mid,h*.46f+d(4),mode==0?mid:w-d(24),h*.46f+d(58),d(27),IVORY);
            text(c,"6 СТРУН",13,mode==0?BG:MUTED,w*.27f,h*.46f+d(38),Paint.Align.CENTER,true);
            text(c,"УКУЛЕЛЕ",13,mode==1?BG:MUTED,w*.73f,h*.46f+d(38),Paint.Align.CENTER,true);
            float by=h-d(150); round(c,d(20),by,w-d(20),by+d(72),d(36),CORAL);
            text(c,"ПРИСТУПИТЬ",16,BG,w/2,by+d(45),Paint.Align.CENTER,true);
            float ey=h-d(230);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.5f));p.setColor(SKY);c.drawRoundRect(d(20),ey,w-d(20),ey+d(60),d(30),d(30),p);
            text(c,"НАСТРОИТЬ НА СЛУХ",13,SKY,w/2,ey+d(38),Paint.Align.CENTER,true);
            text(c,"ПОСЛЕДНЯЯ НАСТРОЙКА БЫЛА  "+lastTune.toUpperCase(Locale.getDefault()),11,MUTED,w/2,h-d(40),Paint.Align.CENTER,true);
            invalidateDelayed();
        }
        void drawTuner(Canvas c){
            updateGaugePhysics();
            float w=getWidth(),h=contentHeight(),cx=w/2;drawBack(c);
            text(c,mode==0?"ШЕСТИСТРУННАЯ ГИТАРА":"УКУЛЕЛЕ",10,MUTED,cx,d(73),Paint.Align.CENTER,true);
            round(c,w-d(198),d(17),w-d(112),d(43),d(13),CARD);text(c,"СЦЕНА",10,IVORY,w-d(155),d(35),Paint.Align.CENTER,true);
            round(c,w-d(104),d(17),w-d(18),d(43),d(13),autoMode?LIME:SKY);
            text(c,autoMode?"АВТО":"РУЧНОЙ",10,BG,w-d(61),d(35),Paint.Align.CENTER,true);

            int count=notes[mode].length; float gap=Math.min(d(52),(w-d(58))/(count-1)); float start=cx-gap*(count-1)/2;
            for(int i=0;i<count;i++){
                float bx=start+i*gap; int col=tuned[mode][i]?LIME:(i==stringIndex?IVORY:MUTED);
                if(i==stringIndex){
                    p.setColor(i%2==0?CORAL:SKY);p.setStyle(Paint.Style.FILL);c.drawCircle(bx,d(118),d(25),p);col=BG;
                }
                if(tuned[mode][i]){p.setColor(LIME);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));c.drawCircle(bx,d(118),d(19),p);}
                displayText(c,notes[mode][i],16,col,bx,d(124),Paint.Align.CENTER);
                text(c,octaves[mode][i],8,col,bx+d(10),d(108),Paint.Align.LEFT,true);
            }
            text(c,"НАЖМИТЕ НОТУ ДЛЯ РУЧНОГО РЕЖИМА И ЗВУКА",8,MUTED,cx,d(154),Paint.Align.CENTER,true);

            final float scaleRange=50, desiredSweep=118;
            RectF arc=new RectF(d(7),d(172),w-d(7),d(590));
            float arcStart=270-desiredSweep/2;
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(d(12));p.setColor(CARD);
            c.drawArc(arc,270-desiredSweep/2,desiredSweep,false,p);
            p.setStrokeWidth(d(targetCents< -3&&pitch>0?7:4));p.setAlpha(targetCents< -3&&pitch>0?255:75);p.setColor(CORAL);c.drawArc(arc,arcStart,desiredSweep*.36f,false,p);
            p.setAlpha(120);p.setStrokeWidth(d(4));p.setColor(IVORY);c.drawArc(arc,arcStart+desiredSweep*.36f,desiredSweep*.28f,false,p);
            p.setStrokeWidth(d(targetCents>3&&pitch>0?7:4));p.setAlpha(targetCents>3&&pitch>0?255:75);p.setColor(SKY);c.drawArc(arc,arcStart+desiredSweep*.64f,desiredSweep*.36f,false,p);p.setAlpha(255);
            p.setColor(LIME);p.setStrokeWidth(d(9));c.drawArc(arc,270-desiredSweep*.055f,desiredSweep*.11f,false,p);
            for(int i=-5;i<=5;i++){
                float angle=270+i*desiredSweep/10; double a=Math.toRadians(angle);float rx=arc.width()/2,ry=arc.height()/2;
                float x=cx+(float)Math.cos(a)*rx,y=arc.centerY()+(float)Math.sin(a)*ry;
                float len=i==0?d(25):d(i%5==0?17:9);p.setStrokeWidth(d(i==0?4:1.2f));p.setColor(i==0?LIME:(i<0?CORAL:(i>0?SKY:IVORY)));
                c.drawLine(x,y,x-(float)Math.cos(a)*len,y-(float)Math.sin(a)*len,p);
            }
            float ratio=(float)Math.max(-1,Math.min(1,needleCents/scaleRange)), needleAngle=270+ratio*desiredSweep/2;
            double na=Math.toRadians(needleAngle);float nx=cx+(float)Math.cos(na)*arc.width()/2,ny=arc.centerY()+(float)Math.sin(na)*arc.height()/2;
            float ux=(float)Math.cos(na),uy=(float)Math.sin(na),px=-uy,py=ux;
            float arrowLength=d(78),tipX=nx+ux*d(5),tipY=ny+uy*d(5),baseX=nx-ux*d(18),baseY=ny-uy*d(18),startX=nx-ux*arrowLength,startY=ny-uy*arrowLength;
            int arrowColor=pitch<0?IVORY:(targetCents< -3?CORAL:(targetCents>3?SKY:LIME));
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(d(7));p.setColor(arrowColor);c.drawLine(startX,startY,baseX,baseY,p);
            Path arrowHead=new Path();arrowHead.moveTo(tipX,tipY);arrowHead.lineTo(baseX+px*d(12),baseY+py*d(12));arrowHead.lineTo(baseX-px*d(12),baseY-py*d(12));arrowHead.close();p.setStyle(Paint.Style.FILL);c.drawPath(arrowHead,p);
            p.setColor(CARD);c.drawCircle(startX,startY,d(8),p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(IVORY);c.drawCircle(startX,startY,d(8),p);
            double la=Math.toRadians(arcStart),ra=Math.toRadians(arcStart+desiredSweep);float lx=cx+(float)Math.cos(la)*arc.width()/2,ly=arc.centerY()+(float)Math.sin(la)*arc.height()/2,rx=cx+(float)Math.cos(ra)*arc.width()/2,ry=arc.centerY()+(float)Math.sin(ra)*arc.height()/2;
            text(c,"−50",10,CORAL,lx,ly+d(28),Paint.Align.CENTER,true);text(c,"+50",10,SKY,rx,ry+d(28),Paint.Align.CENTER,true);

            float noteBob=pitch>0&&Math.abs(targetCents)<10?(float)Math.sin(System.currentTimeMillis()/115.0)*d(2.5f):0;
            displayText(c,notes[mode][stringIndex],96,IVORY,cx,d(390)+noteBob,Paint.Align.CENTER);
            text(c,octaves[mode][stringIndex],19,LIME,cx+d(54),d(330),Paint.Align.LEFT,true);
            String status=pitch<0?"СЫГРАЙТЕ СТРУНУ":Math.abs(targetCents)<=3.5?"ДЕРЖИТЕ ЗВУК":(targetCents<0?"ПОДТЯНИТЕ ВЫШЕ":"ОСЛАБЬТЕ НИЖЕ");
            int sc=pitch<0?MUTED:(targetCents< -3?CORAL:(targetCents>3?SKY:LIME));text(c,status,13,sc,cx,d(438),Paint.Align.CENTER,true);
            if(pitch>0) text(c,String.format(Locale.US,"%+.1f ЦЕНТА  ·  %.2f Hz",targetCents,pitch),12,MUTED,cx,d(466),Paint.Align.CENTER,false);

            float progress=stableSince==0?0:Math.min(1,(System.currentTimeMillis()-stableSince)/700f);
            round(c,d(42),d(492),w-d(42),d(498),d(3),Color.rgb(43,43,49));
            if(progress>0)round(c,d(42),d(492),d(42)+(w-d(84))*progress,d(498),d(3),LIME);
            if(progress>0&&progress<1)text(c,"УДЕРЖИВАЙТЕ  "+Math.round(progress*100)+"%",9,LIME,cx,d(522),Paint.Align.CENTER,true);

            drawNoiseMeter(c,w,h-d(100));
            text(c,autoMode?"Сыграйте любую струну · определю автоматически":"Ручной режим · выберите струну",10,MUTED,cx,h-d(27),Paint.Align.CENTER,false);
            p.setColor(LIME);c.drawCircle(cx-d(autoMode?145:103),h-d(31),d(3.5f),p);
            if(permissionDenied){round(c,d(24),h-d(130),w-d(24),h-d(72),d(16),Color.rgb(61,35,37));text(c,"Нужен доступ к микрофону",13,IVORY,cx,h-d(95),Paint.Align.CENTER,true);}
            drawPraise(c,cx);
            invalidateDelayed();
        }

        void drawNoiseMeter(Canvas c,float w,float y){
            float level=(float)Math.max(0,Math.min(1,(inputDb+60)/35)),left=d(58),right=w-d(58),gap=(right-left)/8;
            boolean noisy=pitch<0&&inputDb>-40&&inputClarity<.55;int active=noisy?CORAL:(pitch>0?LIME:SKY);
            for(int i=0;i<8;i++){round(c,left+i*gap,y,left+i*gap+gap-d(4),y+d(i<Math.round(level*8)?7:3),d(3),i<Math.round(level*8)?active:CARD);}
            String label=noisy?"ШУМНО · ПОДОЙДИТЕ БЛИЖЕ":(pitch>0?"СИГНАЛ ЧИСТЫЙ":"УРОВЕНЬ ОКРУЖЕНИЯ");
            text(c,label,9,noisy?CORAL:(pitch>0?LIME:MUTED),w/2,y-d(9),Paint.Align.CENTER,true);
        }

        void updateGaugePhysics(){
            long now=System.currentTimeMillis(); if(lastFrame==0)lastFrame=now; double frames=Math.min(2.5,(now-lastFrame)/16.666);lastFrame=now;
            // Responsive low-pass smoothing: removes jitter without spring overshoot or long lag.
            double blend=1-Math.pow(.86,frames);needleCents+=(targetCents-needleCents)*blend;
        }

        void drawPraise(Canvas c,float cx){
            long now=System.currentTimeMillis();if(now>=praiseUntil)return;float t=Math.min(1,(now-praiseStarted)/1400f);
            float elastic=(float)(1-Math.pow(2,-9*t)*Math.cos(t*5*Math.PI));float alpha=Math.min(1,(1-t)*3);
            float cy=d(555)-d(20)*t,scale=.45f+.6f*elastic;c.save();c.rotate(-8+12*(float)Math.sin(t*Math.PI),cx,cy);c.scale(scale,scale,cx,cy);
            p.setAlpha((int)(255*alpha));round(c,cx-d(106),cy-d(34),cx+d(106),cy+d(34),d(21),LIME);
            Path tail=new Path();tail.moveTo(cx+d(55),cy+d(27));tail.lineTo(cx+d(79),cy+d(54));tail.lineTo(cx+d(84),cy+d(25));tail.close();p.setColor(LIME);c.drawPath(tail,p);
            displayText(c,praise[praiseIndex%praise.length],34,BG,cx,cy+d(12),Paint.Align.CENTER);p.setAlpha(255);c.restore();
            for(int i=0;i<16;i++){double a=i*Math.PI*2/16+.3;float r=d(36+92*t),x=cx+(float)Math.cos(a)*r,y=cy+(float)Math.sin(a)*r*.52f;p.setColor(i%3==0?Color.rgb(255,105,132):(i%2==0?IVORY:LIME));p.setAlpha((int)(220*alpha));p.setStyle(Paint.Style.FILL);if(i%2==0)c.drawCircle(x,y,d(2.5f+2*t),p);else{p.setStrokeWidth(d(3));c.drawLine(x,y,x+(float)Math.cos(a)*d(11),y+(float)Math.sin(a)*d(11),p);}}p.setAlpha(255);
        }
        void drawStage(Canvas c){
            updateGaugePhysics();float w=getWidth(),h=contentHeight(),cx=w/2;drawBack(c);
            round(c,cx-d(50),d(16),cx+d(50),d(45),d(15),IVORY);text(c,"СЦЕНА",11,BG,cx,d(36),Paint.Align.CENTER,true);
            float cy=h*.33f;displayText(c,notes[mode][stringIndex],150,IVORY,cx,cy,Paint.Align.CENTER);text(c,octaves[mode][stringIndex],24,LIME,cx+d(80),cy-d(78),Paint.Align.LEFT,true);
            if(pitch>0)text(c,String.format(Locale.US,"%.2f Hz",pitch),17,MUTED,cx,cy+d(45),Paint.Align.CENTER,false);
            float left=d(25),right=w-d(25),barY=h*.57f,center=(left+right)/2,half=(right-left)/2;
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(d(18));p.setColor(CARD);c.drawLine(left,barY,right,barY,p);
            p.setStrokeWidth(d(targetCents< -3&&pitch>0?13:7));p.setAlpha(targetCents< -3&&pitch>0?255:80);p.setColor(CORAL);c.drawLine(left,barY,center-d(12),barY,p);
            p.setStrokeWidth(d(targetCents>3&&pitch>0?13:7));p.setAlpha(targetCents>3&&pitch>0?255:80);p.setColor(SKY);c.drawLine(center+d(12),barY,right,barY,p);p.setAlpha(255);
            p.setColor(LIME);p.setStrokeWidth(d(6));c.drawLine(center,barY-d(23),center,barY+d(23),p);
            float pointerX=center+(float)Math.max(-1,Math.min(1,needleCents/50))*half;
            Path caret=new Path();caret.moveTo(pointerX,barY-d(8));caret.lineTo(pointerX-d(18),barY-d(42));caret.lineTo(pointerX+d(18),barY-d(42));caret.close();p.setStyle(Paint.Style.FILL);p.setColor(pitch<0?IVORY:(targetCents< -3?CORAL:(targetCents>3?SKY:LIME)));c.drawPath(caret,p);
            String status=pitch<0?"СЫГРАЙТЕ СТРУНУ":Math.abs(targetCents)<=3.5?"ТОЧНО":(targetCents<0?"НИЖЕ НОРМЫ":"ВЫШЕ НОРМЫ");
            displayText(c,status,24,pitch<0?IVORY:(targetCents< -3?CORAL:(targetCents>3?SKY:LIME)),cx,barY+d(82),Paint.Align.CENTER);
            drawNoiseMeter(c,w,h-d(123));round(c,d(28),h-d(74),w-d(28),h-d(22),d(26),IVORY);text(c,"ОБЫЧНЫЙ ВИД",12,BG,cx,h-d(42),Paint.Align.CENTER,true);drawPraise(c,cx);invalidateDelayed();
        }

        void drawEar(Canvas c){
            float w=getWidth(),h=contentHeight(),cx=w/2;drawBack(c);text(c,"НАСТРОЙКА НА СЛУХ",11,MUTED,cx,d(36),Paint.Align.CENTER,true);
            int count=notes[mode].length;float gap=Math.min(d(52),(w-d(58))/(count-1)),start=cx-gap*(count-1)/2;
            for(int i=0;i<count;i++){float bx=start+i*gap;if(i==earIndex){p.setStyle(Paint.Style.FILL);p.setColor(SKY);c.drawCircle(bx,d(115),d(24),p);}displayText(c,notes[mode][i],16,i==earIndex?BG:MUTED,bx,d(121),Paint.Align.CENTER);text(c,octaves[mode][i],8,i==earIndex?BG:MUTED,bx+d(10),d(105),Paint.Align.LEFT,true);}
            float cy=h*.39f;if(System.currentTimeMillis()<tonePlayingUntil){float t=(tonePlayingUntil-System.currentTimeMillis())/1280f;for(int i=0;i<3;i++){float wave=(1-t+i*.27f)%1;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setAlpha((int)(100*(1-wave)));p.setColor(SKY);c.drawCircle(cx,cy,d(72+75*wave),p);}p.setAlpha(255);}
            displayText(c,notes[mode][earIndex],122,IVORY,cx,cy+d(38),Paint.Align.CENTER);text(c,octaves[mode][earIndex],22,LIME,cx+d(68),cy-d(48),Paint.Align.LEFT,true);
            text(c,String.format(Locale.US,"ЭТАЛОН  %.2f Hz",targets[mode][earIndex]),12,MUTED,cx,cy+d(82),Paint.Align.CENTER,true);
            text(c,"Слушайте тон и подстройте струну до совпадения",12,MUTED,cx,cy+d(122),Paint.Align.CENTER,false);
            float playY=h-d(220);round(c,d(24),playY,w-d(24),playY+d(62),d(31),CORAL);text(c,"▶  ПРОСЛУШАТЬ ЕЩЁ РАЗ",13,BG,cx,playY+d(39),Paint.Align.CENTER,true);
            float nextY=h-d(140);round(c,d(24),nextY,w-d(24),nextY+d(62),d(31),IVORY);text(c,earIndex==count-1?"СНАЧАЛА":"СЛЕДУЮЩАЯ СТРУНА  →",13,BG,cx,nextY+d(39),Paint.Align.CENTER,true);invalidateDelayed();
        }
        void drawFinish(Canvas c){
            float w=getWidth(),h=contentHeight(),cx=w/2, t=(System.currentTimeMillis()-screenStarted)/1000f;
            drawBrand(c);
            float enter=Math.min(1,t/.75f),pop=(float)(1-Math.pow(2,-9*enter)*Math.cos(enter*4.5*Math.PI));
            float bounce=(float)Math.sin(t*3)*d(5),cy=d(270)+bounce;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));
            for(int i=0;i<3;i++){float pulse=(t*.34f+i*.31f)%1;p.setAlpha((int)(100*(1-pulse)));p.setColor(LIME);c.drawCircle(cx,cy,d(90+75*pulse),p);}p.setAlpha(255);
            c.save();c.scale(pop,pop,cx,cy);p.setStyle(Paint.Style.FILL);p.setColor(LIME);c.drawCircle(cx,cy,d(82),p);
            float blink=Math.abs(Math.sin(t*1.7))>.96?d(2):d(7);p.setColor(BG);c.drawOval(new RectF(cx-d(34),cy-d(26)-blink,cx-d(20),cy-d(26)+blink),p);c.drawOval(new RectF(cx+d(20),cy-d(26)-blink,cx+d(34),cy-d(26)+blink),p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(7));p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(new RectF(cx-d(38),cy-d(20),cx+d(38),cy+d(40)),15,150,false,p);c.restore();
            displayText(c,"КЛАСС!",48,IVORY,cx,d(420),Paint.Align.CENTER);
            text(c,"ИНСТРУМЕНТ НАСТРОЕН",13,LIME,cx,d(455),Paint.Align.CENTER,true);
            round(c,d(30),h-d(125),w-d(30),h-d(63),d(31),IVORY);text(c,"НА ГЛАВНУЮ",14,BG,cx,h-d(86),Paint.Align.CENTER,true);
            updateParticles(); for(Particle q:particles){p.setColor(q.color);p.setStyle(Paint.Style.FILL);c.save();c.rotate(q.rot,q.x,q.y);c.drawRect(q.x-d(3),q.y-d(7),q.x+d(3),q.y+d(7),p);c.restore();}
            invalidateDelayed();
        }
        void invalidateDelayed(){ postInvalidateDelayed(16); }

        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_UP)return true;float x=e.getX(),y=e.getY()-topInset,h=contentHeight(),w=getWidth();
            if(screen==0){
                if(y>h*.43f&&y<h*.57f){mode=x<w/2?0:1;performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();}
                else if(y>h-d(245)&&y<h-d(160)){screen=3;earIndex=0;tonePlayingUntil=System.currentTimeMillis()+1280;playReference(targets[mode][earIndex]);performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);invalidate();}
                else if(y>h-d(180)&&y<h-d(55)){screen=1;stringIndex=0;pitch=-1;targetCents=needleCents=0;stableSince=0;candidateString=-1;candidateSince=0;advancing=false;autoMode=true;Arrays.fill(tuned[mode],false);screenStarted=System.currentTimeMillis();beginListening();performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);invalidate();}
            } else if(screen==1){
                if(y<d(62)&&x<d(82)){stopListening();screen=0;stageMode=false;stableSince=0;pitch=-1;performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();}
                else if(stageMode&&y>h-d(95)){stageMode=false;performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();}
                else if(!stageMode&&y<d(58)&&x>w-d(205)&&x<w-d(108)){stageMode=true;performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();}
                else if(!stageMode&&y<d(58)&&x>w-d(108)){autoMode=!autoMode;candidateString=-1;candidateSince=0;stableSince=0;performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();}
                else if(!stageMode&&y>d(82)&&y<d(162)){
                    int count=notes[mode].length;float gap=Math.min(d(52),(w-d(58))/(count-1)),start=w/2-gap*(count-1)/2;
                    int chosen=Math.max(0,Math.min(count-1,Math.round((x-start)/gap)));stringIndex=chosen;autoMode=false;advancing=false;stableSince=0;candidateString=-1;pitch=-1;targetCents=0;
                    muteUntil=System.currentTimeMillis()+1500;playReference(targets[mode][chosen]);performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();
                }
            } else if(screen==2&&y>h-d(150)){screen=0;particles.clear();invalidate();}
            else if(screen==3){
                if(y<d(62)&&x<d(82)){screen=0;invalidate();}
                else if(y>d(82)&&y<d(155)){int count=notes[mode].length;float gap=Math.min(d(52),(w-d(58))/(count-1)),start=w/2-gap*(count-1)/2;earIndex=Math.max(0,Math.min(count-1,Math.round((x-start)/gap)));tonePlayingUntil=System.currentTimeMillis()+1280;playReference(targets[mode][earIndex]);invalidate();}
                else if(y>h-d(235)&&y<h-d(145)){tonePlayingUntil=System.currentTimeMillis()+1280;playReference(targets[mode][earIndex]);invalidate();}
                else if(y>h-d(155)&&y<h-d(60)){earIndex=(earIndex+1)%notes[mode].length;tonePlayingUntil=System.currentTimeMillis()+1280;playReference(targets[mode][earIndex]);performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);invalidate();}
            }
            performClick();
            return true;
        }
        void onAudioSample(double hz,double db,double clarity){inputDb=db;inputClarity=clarity;onPitch(hz);}
        void onPitch(double hz){
            if(screen!=1||System.currentTimeMillis()<muteUntil)return;
            if(hz<=0){pitch=-1;targetCents=0;stableSince=0;candidateString=-1;candidateSince=0;invalidate();return;}
            long now=System.currentTimeMillis();
            if(autoMode&&!advancing){
                int detected=identifyString(hz);
                if(detected<0){pitch=-1;targetCents=0;stableSince=0;candidateString=-1;candidateSince=0;invalidate();return;}
                if(detected!=stringIndex){
                    if(candidateString!=detected){candidateString=detected;candidateSince=now;stableSince=0;}
                    else if(now-candidateSince>=150){stringIndex=detected;candidateString=-1;stableSince=0;targetCents=0;}
                }else candidateString=-1;
            }
            double target=targets[mode][stringIndex],measured=hz;
            int harmonic=(int)Math.round(hz/target);
            if(harmonic>=2&&harmonic<=4){double harmonicError=1200*Math.log(hz/(target*harmonic))/Math.log(2);if(Math.abs(harmonicError)<55)measured=hz/harmonic;}
            pitch=measured;cents=1200*Math.log(measured/target)/Math.log(2);targetCents=Math.max(-100,Math.min(100,cents));
            if(!advancing&&Math.abs(cents)<=3.5){if(stableSince==0)stableSince=now;}else if(!advancing)stableSince=0;
            if(!advancing&&stableSince>0&&now-stableSince>=700){
                advancing=true;tuned[mode][stringIndex]=true;praiseIndex=(praiseIndex+1)%praise.length;praiseStarted=now;praiseUntil=now+1450;muteUntil=now+800;playSuccessSound();performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                final int captured=stringIndex;new Handler(Looper.getMainLooper()).postDelayed(()->{
                    if(screen!=1||stringIndex!=captured)return;
                    if(allTuned()){finishTuning();return;}
                    targetCents=0;pitch=-1;advancing=false;stableSince=0;candidateString=-1;invalidate();
                },950);
            }
            invalidate();
        }
        int identifyString(double hz){
            int directIndex=0,harmonicIndex=0;double directError=Double.MAX_VALUE,harmonicError=Double.MAX_VALUE;
            for(int i=0;i<targets[mode].length;i++){
                double target=targets[mode][i],direct=Math.abs(1200*Math.log(hz/target)/Math.log(2));
                if(direct<directError){directError=direct;directIndex=i;}
                for(int h=2;h<=4;h++){
                    double error=Math.abs(1200*Math.log(hz/(target*h))/Math.log(2));
                    if(error<harmonicError){harmonicError=error;harmonicIndex=i;}
                }
            }
            // Prefer the actual fundamental; use a harmonic only when it is clearly more plausible.
            if(harmonicError+35<directError&&harmonicError<45)return harmonicIndex;
            return directError<165?directIndex:-1;
        }
        boolean allTuned(){for(boolean value:tuned[mode])if(!value)return false;return true;}
        void finishTuning(){
            stopListening(); screen=2;screenStarted=System.currentTimeMillis();
            String date=new SimpleDateFormat("d MMMM",new Locale("ru")).format(new Date());lastTune=date;getPreferences(0).edit().putString("last",date).apply();
            for(int i=0;i<90;i++)particles.add(new Particle()); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        void updateParticles(){float dt=.016f;for(Particle q:particles){q.vy+=d(170)*dt;q.x+=q.vx*dt;q.y+=q.vy*dt;q.rot+=q.vr;if(q.y>contentHeight()+d(20)){q.reset();q.y=-d(random.nextInt(140));}}}
        @Override public boolean performClick(){ super.performClick(); return true; }
        class Particle{float x,y,vx,vy,rot,vr;int color;Particle(){reset();y=random.nextInt(Math.max(1,getHeight()));}void reset(){x=random.nextInt(Math.max(1,getWidth()));y=-20;vx=d(random.nextInt(81)-40);vy=d(random.nextInt(100)+35);rot=random.nextInt(360);vr=random.nextInt(14)-7;int[] cs={LIME,IVORY,CORAL,SKY};color=cs[random.nextInt(cs.length)];}}
    }
}
