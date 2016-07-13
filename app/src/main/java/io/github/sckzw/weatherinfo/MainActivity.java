package io.github.sckzw.weatherinfo;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.util.Log;
import android.view.View;
import android.support.v7.app.AppCompatActivity;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final UUID BLUETOOTH_SERVICE_UUID = UUID.fromString( "4239bea0-1a85-11e3-866a-0002a5d5c51b" );
    private static final String BLUETOOTH_SERVICE_NAME = "KENWOOD Drive Info. for Android";
    private static final String WEATHER_INFO_URL = "http://api.wunderground.com/api/a81e75c244e6ca44/";
    private static final String WEATHER_DAY_OF_WEEK[] = { "e697a5", "e69c88", "e781ab", "e6b0b4", "e69ca8", "e98791", "e59c9f" };
    private static final String LOG_TAG = "WeatherInfo";

    BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    BluetoothThread mThread;
    Handler mHandler;
    EditText mEditText;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_main );

        mEditText = (EditText)findViewById(R.id.editText);
        mHandler = new Handler();

        StrictMode.setThreadPolicy( new StrictMode.ThreadPolicy.Builder().permitAll().build() );

        mThread = new BluetoothThread();
        mThread.start();

        mEditText.append( "Start.\n" );
        Log.d( LOG_TAG, "Start." );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if ( mThread != null ) {
            mThread.cancel();
        }
    }

    public void onClickButtonClear( View view ) {
        mEditText.setText( "" );
    }

    private class BluetoothThread extends Thread {
        private BluetoothServerSocket mServerSocket = null;

        private static final int COMMAND_CONNECT = 1;
        private static final int COMMAND_DISCONNECT = 2;
        private static final int COMMAND_RETURN_CONNECT = 32769;
        private static final int COMMAND_NOTIFY_SYSTEM_STATUS = 32771;
        private static final int COMMAND_EVENT_WEATHER_INFO_UPDATE = 49;
        private static final int COMMAND_RETURN_WEATHER_INFO_UPDATE = 32817;
        private static final int COMMAND_NOTIFY_WEATHER_INFO_UPDATE = 32819;
        private static final int COMMAND_GET_WEATHER_INFO = 53;
        private static final int COMMAND_RETURN_WEATHER_INFO_SEGMENT = 32821;

        public BluetoothThread() {
            try {
                mServerSocket = mBluetoothAdapter.listenUsingRfcommWithServiceRecord( BLUETOOTH_SERVICE_NAME, BLUETOOTH_SERVICE_UUID );
            }
            catch ( IOException ex ) {
                Log.e( LOG_TAG, "Fail to listen.", ex );
            }
        }

        public void run() {
            BluetoothSocket socket = null;

            while ( true ) {
                try {
                    mHandler.post( new Runnable() { public void run() { mEditText.append( "Accept.\n" ); } } );
                    Log.d( LOG_TAG, "Accept." );

                    if ( mServerSocket == null ) {
                        mServerSocket = mBluetoothAdapter.listenUsingRfcommWithServiceRecord( BLUETOOTH_SERVICE_NAME, BLUETOOTH_SERVICE_UUID );
                    }

                    socket = mServerSocket.accept();
                }
                catch ( IOException ex ) {
                    mHandler.post( new Runnable() { public void run() { mEditText.append( "Fail to accept.\n" ); } } );
                    Log.e( LOG_TAG, "Fail to accept.", ex);

                    break;
                }

                mHandler.post( new Runnable() { public void run() { mEditText.append( "A connection was accepted.\n" ); } } );
                Log.d( LOG_TAG, "A connection was accepted." );

                if ( socket != null ) {
                    connect( socket );
                }

                mHandler.post( new Runnable() { public void run() { mEditText.append( "The session was closed.\nListen again.\n" ); } } );
                Log.d( LOG_TAG, "The session was closed.\nListen again." );

                try {
                    mServerSocket.close();
                    mServerSocket = null;
                }
                catch ( IOException ex ) {
                    mHandler.post( new Runnable() { public void run() { mEditText.append( "Fail to close.\n" ); } } );
                    Log.e( LOG_TAG, "Fail to close.", ex);

                    break;
                }
            }
        }

        private void connect( BluetoothSocket socket ) {
            boolean connection = true;
            byte[] command_data = new byte[1024];
            int command_delimiter;
            int command_type;
            int command_size;
            int command_id;
            byte request_count = 0;
            byte response_count = 1;
            String weatherInfo = "";
            byte weather_type;

            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                mHandler.post( new Runnable() { public void run() { mEditText.append( "Connection established.\n" ); } } );
                Log.d( LOG_TAG, "Connection established." );

                while ( connection ) {
                    // コマンド開始デリミタを検索する
                    do {
                        command_delimiter = in.read();
                    } while ( command_delimiter != 0xfe );

                    // コマンドタイプ(リクエスト or レスポンス)を取得する
                    command_type = in.read();

                    // フローコントロールデータは不要なので読み捨てる
                    if ( in.skip( 4 ) != 4 ) {
                        mHandler.post( new Runnable() { public void run() { mEditText.append( "Invalid skip size.\n" ); } } );
                        break;
                    }

                    // コマンドサイズを取得する
                    command_size = in.read() * 256;
                    command_size += in.read();

                    // コマンドサイズが0の場合、コマンド終了デリミタを探索する
                    if ( command_size == 0 ) {
                        do {
                            command_delimiter = in.read();
                        } while ( command_delimiter != 0xfc );

                        continue;
                    }

                    // コマンドデータを取得し、データサイズが不正の場合は終了する
                    if ( in.read( command_data, 0, command_size ) != command_size ) {
                        mHandler.post( new Runnable() { public void run() { mEditText.append( "Invalid command size.\n" ); } });
                        break;
                    }

                    // コマンド終了デリミタを探索する
                    do {
                        command_delimiter = in.read();
                    } while ( command_delimiter != 0xfc );

                    // コマンドデータのバイトバッファを作成する
                    ByteBuffer byteBuffer = ByteBuffer.wrap( command_data, 0, command_size );

                    // コマンドIDを取得する
                    command_id = byteBuffer.getShort(); // command_data[0] * 256 + command_data[1];

                    // コマンドIDごとにコマンド処理を実行する
                    switch ( command_id ) {
                        case COMMAND_CONNECT:
                            sendResponse( out, response_count ++ );

                            sendRequest( out, request_count ++, COMMAND_RETURN_CONNECT, "00000000000101" );
                            sendRequest( out, request_count ++, COMMAND_NOTIFY_SYSTEM_STATUS, "5600000000010400" );

                            break;
                        case COMMAND_DISCONNECT:
                            sendResponse( out, response_count );

                            connection = false;

                            break;
                        case COMMAND_EVENT_WEATHER_INFO_UPDATE:
                            sendResponse( out, response_count ++ );

                            // 天気予報の種類を取得する
                            weather_type = byteBuffer.get( 4 ); // command_data[4];

                            // 経緯・緯度を取得する
                            byteBuffer.position( 6 );
                            byte[] bytes = new byte[byteBuffer.remaining()];
                            byteBuffer.get( bytes );

                            // 度分秒を十進法に変換する
                            // 日本測地系を世界測地系に変換する必要があるが面倒なので誤差を承知で省略する
                            String[] coordinate = new String( bytes ).split( "[EN.\0]" );

                            float longitude = Float.parseFloat( coordinate[1] ) +
                                    Float.parseFloat( coordinate[2]) / 60f +
                                    Float.parseFloat( coordinate[3] + "." + coordinate[4] ) / 3600f;
                            float latitude  = Float.parseFloat( coordinate[5] ) +
                                    Float.parseFloat( coordinate[6]) / 60f +
                                    Float.parseFloat( coordinate[7] + "." + coordinate[8] ) / 3600f;

                            sendRequest( out, request_count ++, COMMAND_RETURN_WEATHER_INFO_UPDATE, "000000000001" );

                            // 天気情報を取得しデータサイズを送信する
                            weatherInfo = getWeatherInfo( latitude, longitude, weather_type );

                            sendRequest( out, request_count ++, COMMAND_NOTIFY_WEATHER_INFO_UPDATE, "0000000000010000" + String.format( "%04x", weatherInfo.length() / 2 - 14 ) );

                            break;
                        case COMMAND_GET_WEATHER_INFO:
                            sendResponse( out, response_count ++ );

                            // 天気情報を送信する
                            sendRequest( out, request_count ++, COMMAND_RETURN_WEATHER_INFO_SEGMENT, weatherInfo );

                            break;
                        default:
                            sendResponse( out, response_count ++ );
                            break;
                    }
                }

                socket.close();
            }
            catch ( IndexOutOfBoundsException ex ) {
                mHandler.post( new Runnable() { public void run() { mEditText.append( "Index out of bounds exception.\n" ); } });
                Log.e( LOG_TAG, "Index out of bounds exception", ex );
            }
            catch ( IOException ex ) {
                mHandler.post( new Runnable() { public void run() { mEditText.append( "IO exception.\n" ); } });
                Log.e( LOG_TAG, "IO exception.", ex);
            }
        }

        public void cancel() {
            try {
                mServerSocket.close();

                mHandler.post( new Runnable() { public void run() { mEditText.append( "The server socket is closed.\n" ); } } );
                Log.d( LOG_TAG, "The server socket is closed." );
            } catch ( IOException ex ) {
                mHandler.post( new Runnable() { public void run() { mEditText.append( "IO exception.\n" ); } });
                Log.e( LOG_TAG, "IO exception.", ex);
            }
        }

        /**
         * リクエストデータを送信する
         *
         * @param out リクエストデータ出力ストリーム
         * @param request_count フローコントロールカウンタ
         * @param command_id リクエストコマンドID
         * @param command_data_hex リクエストデータ(HEX文字列)
         * @throws IOException
         */
        private void sendRequest( OutputStream out, byte request_count, int command_id, String command_data_hex ) throws IOException {
            byte[] command_data;
            byte[] escaped_data;
            int command_data_size;
            int escaped_data_size = 0;
            int i, j;

            // コマンドデータのHEX文字列からバイト列を取得する
            command_data = hex2bin( command_data_hex );
            // コマンドデータのサイズを計算する、+2はコマンドIDのサイズ
            command_data_size = command_data.length + 2;

            // コマンドデータのデリミタをエスケープした際のデータサイズを算出する
            for ( i = 0; i < command_data.length; i ++ ) {
                if ( command_data[i] == (byte)0xfe || command_data[i] == (byte)0xfd || command_data[i] == (byte)0xfc )
                    escaped_data_size ++;

                escaped_data_size ++;
            }

            // コマンドデータのデリミタをエスケープしたデータを生成する
            escaped_data = new byte[escaped_data_size];

            for ( i = 0, j = 0; i < command_data.length; i ++ ) {
                switch ( command_data[i] ) {
                    case (byte)0xfe:
                        escaped_data[j ++] = (byte)0xfd;
                        escaped_data[j ++] = (byte)0x5e;
                        break;
                    case (byte)0xfd:
                        escaped_data[j ++] = (byte)0xfd;
                        escaped_data[j ++] = (byte)0x5d;
                        break;
                    case (byte)0xfc:
                        escaped_data[j ++] = (byte)0xfd;
                        escaped_data[j ++] = (byte)0x5c;
                        break;
                    default:
                        escaped_data[j ++] = command_data[i];
                        break;
                }
            }

            // リクエスト開始データ、コマンドデータサイズ、コマンドIDを送信する
            out.write( new byte[]{ (byte)0xfe, (byte)0x00, request_count, (byte)0x01, (byte)0x00, (byte)0x00,
                    (byte)( command_data_size >> 8 & 0xff ), (byte)( command_data_size & 0xff ),
                    (byte)( command_id >> 8 & 0xff ), (byte)( command_id & 0xff ) } );

            // コマンドデータ(エスケープ後)を送信する
            out.write( escaped_data );

            // リクエスト終了データを送信する
            out.write( new byte[] { (byte)0x00, (byte)0x00, (byte)0xfc } );

            out.flush();
        }

        /**
         * レスポンスデータを送信する
         *
         * @param out レスポンスデータ出力ストリーム
         * @param response_count フローコントロールカウンタ
         * @throws IOException
         */
        private void sendResponse( OutputStream out, byte response_count ) throws IOException {
            // レスポンスデータを送信する
            out.write( new byte[]{ (byte)0xfe, (byte)0x01, response_count, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xfc } );
            out.flush();
        }

        /**
         * 天気情報を取得する
         *
         * @param latitude 緯度
         * @param longitude 経度
         * @param type 天気情報の種類(1: 日ごと, 1以外: 時間ごと)
         * @return 天気情報(HEX文字列)
         */
        private String getWeatherInfo( float latitude, float longitude, int type ) {
            String url;
            String jsonData;
            String weatherInfo = "";
            int weatherInfoSize;
            int weatherInfoCount;
            JSONObject rootObject = null;
            JSONObject observationObject = null;
            JSONArray forecastArray = null;
            Calendar calendar;
            Date date;

            try {
                if ( type == 0x01 ) {
                    // 日ごとの天気情報サイトのURLを設定する
                    url = WEATHER_INFO_URL + "forecast10day/q/" + latitude + "," + longitude + ".json";

                    // 天気情報サイトからJSONデータを取得する
                    jsonData = getURLData( url );

                    // JSONオブジェクトを作成し日ごとの天気情報を取得する
                    rootObject = new JSONObject( jsonData );
                    forecastArray = rootObject.getJSONObject( "forecast" ).getJSONObject( "simpleforecast" ).getJSONArray( "forecastday" );
                    weatherInfoCount = forecastArray.length();

                    // 天気情報数を制限する
                    // if ( weatherInfoCount > 7 )
                    //     weatherInfoCount = 7;

                    // 日ごとの天気情報を解析する
                    for ( int i = 0; i < weatherInfoCount; i ++ ) {
                        JSONObject forecastDayObject = forecastArray.getJSONObject( i );

                        // 日付情報からCalendarオブジェクトを取得する
                        JSONObject dateObject = forecastDayObject.getJSONObject( "date" );
                        date = new Date( dateObject.getLong( "epoch" ) * 1000 );
                        calendar = Calendar.getInstance();
                        calendar.setTime( date );

                        // 天気コードを取得する
                        byte weatherCode = getWeatherCodeFromCondition( forecastDayObject.getString( "conditions" ) );
                        weatherCode *= 4; // サイトの天気コードを送信先デバイス向けに変換する

                        // 気温情報を取得する
                        byte highTemp = (byte)forecastDayObject.getJSONObject( "high" ).getInt( "celsius" );
                        byte lowTemp  = (byte)forecastDayObject.getJSONObject( "low"  ).getInt( "celsius" );

                        // 天気情報を作成する
                        weatherInfo += String.format( "0c%02x%02x%s00%02x0081%02x%02x%02x",
                                (byte)( calendar.get( Calendar.MONTH ) + 1 ),
                                (byte)calendar.get( Calendar.DAY_OF_MONTH ),
                                WEATHER_DAY_OF_WEEK[calendar.get( Calendar.DAY_OF_WEEK ) - 1], // UTF-8エンコーディングされた曜日のHEX文字列
                                weatherCode,
                                highTemp,
                                lowTemp,
                                (byte)forecastDayObject.getInt( "avehumidity" )
                        );
                    }
                }
                else {
                    // 時間ごとの天気情報サイトのURLを設定する
                    url = WEATHER_INFO_URL + "hourly/q/" + latitude + "," + longitude + ".json";

                    // 天気情報サイトからJSONデータを取得する
                    jsonData = getURLData( url );

                    // JSONオブジェクトを作成し時間ごとの天気情報を取得する
                    rootObject = new JSONObject( jsonData );
                    forecastArray = rootObject.getJSONArray( "hourly_forecast" );
                    weatherInfoCount = forecastArray.length();

                    // 天気情報数を制限する
                    // if ( weatherInfoCount > 14 )
                    //     weatherInfoCount = 14;

                    // 時間ごとの天気情報を解析する
                    for ( int i = 0; i < weatherInfoCount; i++ ) {
                        JSONObject hourlyForecastObject = forecastArray.getJSONObject( i );

                        // 日付情報からCalendarオブジェクトを取得する
                        JSONObject dateObject = hourlyForecastObject.getJSONObject( "FCTTIME" );
                        date = new Date( dateObject.getLong( "epoch" ) * 1000 );
                        calendar = Calendar.getInstance();
                        calendar.setTime( date );

                        // 天気コードを取得する
                        byte weatherCode = getWeatherCodeFromFCTCode( hourlyForecastObject.getInt( "fctcode" ) );

                        // 気温情報を取得する
                        byte temp = (byte)hourlyForecastObject.getJSONObject( "temp" ).getInt( "metric" );

                        weatherInfo += String.format( "0c%02x%02x%s%02x%02x00%02x8181%02x",
                                (byte)( calendar.get( Calendar.MONTH ) + 1 ),
                                (byte)calendar.get( Calendar.DAY_OF_MONTH ),
                                WEATHER_DAY_OF_WEEK[calendar.get( Calendar.DAY_OF_WEEK ) - 1],
                                (byte)( calendar.get( Calendar.AM_PM ) * 12 + calendar.get( Calendar.HOUR ) ),
                                weatherCode,
                                temp,
                                (byte)hourlyForecastObject.getInt( "humidity" )
                        );
                    }
                }

                // 現在の天気情報サイトのURLを設定する
                url = WEATHER_INFO_URL + "conditions/q/" + latitude + "," + longitude + ".json";

                // 天気情報サイトからJSONデータを取得する
                jsonData = getURLData( url );

                // JSONオブジェクトを作成し現在の天気情報を取得する
                rootObject = new JSONObject( jsonData );
                observationObject = rootObject.getJSONObject( "current_observation" );

                // 時間ごとの天気情報の場合、現在の天気情報も追加する
                if ( type != 0x01 ) {
                    // 現在の日時を取得する
                    date = new Date( observationObject.getLong( "local_epoch" ) * 1000 );
                    calendar = Calendar.getInstance();
                    calendar.setTime( date );

                    // 天気コードを取得する
                    byte weatherCode = getWeatherCodeFromCondition( observationObject.getString( "weather" ) );

                    // 温度情報を取得する
                    byte temp = (byte)observationObject.getInt( "temp_c" );

                    weatherInfo += String.format( "0c%02x%02x%s%02x%02x00%02x8181%02x",
                            (byte)( calendar.get( Calendar.MONTH ) + 1 ),
                            (byte)calendar.get( Calendar.DAY_OF_MONTH ),
                            WEATHER_DAY_OF_WEEK[calendar.get( Calendar.DAY_OF_WEEK ) - 1],
                            (byte)( calendar.get( Calendar.AM_PM ) * 12 + calendar.get( Calendar.HOUR ) ),
                            weatherCode,
                            temp,
                            (byte)0
                    );

                    weatherInfoCount ++;
                }

                // ここまで生成した天気情報のサイズを計算する
                weatherInfoSize = weatherInfoCount * 13;

                // 天気情報のサイズを設定する
                weatherInfo = String.format( "%04x", weatherInfoSize ) + weatherInfo;
                weatherInfoSize += 2;

                // 天気情報の数を設定する
                weatherInfo = String.format( "%02x", weatherInfoCount ) + weatherInfo;
                weatherInfoSize += 1;

                // 天気情報のサイズを設定する
                weatherInfo = String.format( "%04x", weatherInfoSize ) + weatherInfo;
                weatherInfoSize += 2;

                // 不明データを設定する
                weatherInfo = "0000" + bin2hex( "WEA2".getBytes() ) + weatherInfo;
                weatherInfoSize += 6;

                // 天気情報の日時を取得する
                date = new Date( observationObject.getLong( "observation_epoch" ) * 1000 );
                calendar = Calendar.getInstance();
                calendar.setTime( date );

                // 天気情報の日時を設定する
                weatherInfo = bin2hex( String.format( "%04d%02d%02d%02d00",
                        calendar.get( Calendar.YEAR ),
                        calendar.get( Calendar.MONTH ) + 1,
                        calendar.get( Calendar.DAY_OF_MONTH ),
                        calendar.get( Calendar.AM_PM ) * 12 + calendar.get( Calendar.HOUR )
                ).getBytes() ) + weatherInfo;
                weatherInfoSize += 12;

                // 不明データを設定する
                weatherInfo = "ff00" + weatherInfo;
                weatherInfoSize += 2;

                // 都市名を取得する
                JSONObject locationObject = observationObject.getJSONObject( "display_location" );
                byte[] cityName = locationObject.getString( "city" ).getBytes( Charset.forName( "UTF-8" ) );

                // 県名を設定する
                weatherInfo = String.format( "%04x", cityName.length ) + bin2hex( cityName ) + weatherInfo;
                weatherInfoSize += 2 + cityName.length;

                // 市名を設定する
                weatherInfo = String.format( "%04x", cityName.length ) + bin2hex( cityName ) + weatherInfo;
                weatherInfoSize += 2 + cityName.length;

                // 不明データを設定する
                if ( type == 0x01 ) {
                    weatherInfo = "57" + weatherInfo; // 'W'
                    weatherInfoSize ++;
                }
                else {
                    weatherInfo = "44" + weatherInfo; // 'D'
                    weatherInfoSize ++;
                }

                // サイズを設定する
                weatherInfo = String.format( "%04x", cityName.length * 2 + 21 ) + weatherInfo;
                weatherInfoSize += 2;

                // 不明データを設定する
                weatherInfo = "00c8" + bin2hex( "DOC2".getBytes() ) + weatherInfo;
                weatherInfoSize += 6;

                // 天気情報のサイズを設定する
                weatherInfo = String.format( "%04x", weatherInfoSize ) + weatherInfo;

                // 不明データを設定する
                weatherInfo = "000000000001000000000000" + weatherInfo;
            } catch ( JSONException ex ) {
                ex.printStackTrace();
            }

            return weatherInfo;
        }

        private byte getWeatherCodeFromCondition( String weatherCondition ) {
            byte weatherCode;

            if ( weatherCondition.contains( "Clear" ) || weatherCondition.contains( "Sunny" ) ) {
                weatherCode = 0x00; // 晴
            }
            else if ( weatherCondition.contains( "Snow" ) || weatherCondition.contains( "Ice" ) || weatherCondition.contains( "Hail" ) ) {
                weatherCode = 0x03; // 雪
            }
            else if ( weatherCondition.contains( "Rain" ) || weatherCondition.contains( "Thunderstorm" ) || weatherCondition.contains( "Drizzle" ) || weatherCondition.contains( "Squalls" ) ) {
                weatherCode = 0x02; // 雨
            }
            else {
                weatherCode = 0x01; // 曇
            }

            return weatherCode;
        }

        private byte getWeatherCodeFromFCTCode( int FCTCode ) {
            byte weatherCode;

            if ( FCTCode == 1 || FCTCode == 7 ) {
                weatherCode = 0x00; // 晴
            }
            else if ( FCTCode == 9 || ( FCTCode >= 16 && FCTCode <= 24 ) ) {
                weatherCode = 0x03; // 雪
            }
            else if ( FCTCode >= 10 && FCTCode <= 15 ) {
                weatherCode = 0x02; // 雨
            }
            else {
                weatherCode = 0x01; // 曇
            }

            return weatherCode;
        }

        private String getURLData( String url ) {
            String data = "";

            try {
                InputStream in = new URL( url ).openConnection().getInputStream();
                BufferedReader reader = new BufferedReader( new InputStreamReader( in, "UTF-8" ) );
                StringBuilder sb = new StringBuilder();

                String line;
                while ( null != ( line = reader.readLine() ) ) {
                    sb.append( line );
                }

                data = sb.toString();
            } catch ( IOException ex ) {
                ex.printStackTrace();
            }

            return data;
        }

        private byte[] hex2bin( String hex ) {
            byte[] bytes = new byte[hex.length() / 2];

            for ( int i = 0; i < bytes.length; i ++ ) {
                bytes[i] = (byte)Integer.parseInt( hex.substring( i * 2, ( i + 1 ) * 2 ), 16 );
            }

            return bytes;
        }

        private String bin2hex( byte[] bytes ) {
            StringBuilder sb = new StringBuilder();

            for ( int i = 0; i < bytes.length; i ++ ) {
                String s = Integer.toHexString( 0xff & bytes[i] );

                if ( s.length() == 1 ) {
                    sb.append( "0" );
                }

                sb.append( s );
            }

            return sb.toString();
        }
    }
}
