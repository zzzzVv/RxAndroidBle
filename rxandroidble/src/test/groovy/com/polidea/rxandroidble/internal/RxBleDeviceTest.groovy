package com.polidea.rxandroidble.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.jakewharton.rxrelay.BehaviorRelay
import com.polidea.rxandroidble.ConnectionSetup
import com.polidea.rxandroidble.RxBleConnection
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.exceptions.BleAlreadyConnectedException
import com.polidea.rxandroidble.exceptions.BleGattException
import com.polidea.rxandroidble.exceptions.BleGattOperationType
import com.polidea.rxandroidble.internal.connection.Connector
import rx.Observable
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static com.polidea.rxandroidble.RxBleConnection.RxBleConnectionState.*

public class RxBleDeviceTest extends Specification {

    BluetoothDevice mockBluetoothDevice = Mock BluetoothDevice
    Connector mockConnector = Mock Connector
    RxBleConnection mockConnection = Mock RxBleConnection
    PublishSubject<RxBleConnection> mockConnectorEstablishConnectionPublishSubject = PublishSubject.create()
    BehaviorRelay<RxBleConnection.RxBleConnectionState> connectionStateBehaviorRelay = BehaviorRelay.create(DISCONNECTED)
    @Shared BluetoothGatt mockBluetoothGatt = Mock BluetoothGatt
    RxBleDevice rxBleDevice = new RxBleDeviceImpl(mockBluetoothDevice, mockConnector, connectionStateBehaviorRelay)
    TestSubscriber deviceConnectionStateSubscriber = new TestSubscriber()
    static List<EstablishConnectionCaller> establishConnectionCallers = []
    static List<EstablishConnectionTestSetup> establishConnectionTestSetups = []

    def setupSpec() {
        // generating all possible calls to RxBleDevice.establishConnection()
        establishConnectionCallers = [
                new EstablishConnectionCaller(
                        "0",
                        { RxBleDevice d, ConnectionSetup cs -> d.establishConnection(null, cs.autoConnect) }
                ),
                new EstablishConnectionCaller(
                        "1",
                        { RxBleDevice d, ConnectionSetup cs -> d.establishConnection(cs.autoConnect) }
                ),
                new EstablishConnectionCaller(
                        "2",
                        { RxBleDevice d, ConnectionSetup cs -> d.establishConnection(cs) }
                ),
        ]

        // generating all possible ConnectionSetups which will be used by the above
        List<ConnectionSetup> connectionSetups = []
        [
                [true, false], // autoConnect
                [true, false]  // suppressIllegalOperationException
        ].eachCombination {
            connectionSetups << new ConnectionSetup.Builder()
                    .setAutoConnect(it[0])
                    .setSuppressIllegalOperationCheck(it[1])
                    .build()
        }

        // generating all possible calls to RxBleDevice.establishConnection using all possible ConnectionSetups
        [
                establishConnectionCallers,
                connectionSetups
        ].eachCombination {
            establishConnectionTestSetups << new EstablishConnectionTestSetup(
                    it[0],
                    it[1]
            )
        }
    }

    def setup() {
        mockConnector.prepareConnection(_) >> mockConnectorEstablishConnectionPublishSubject
    }

    def "should return the BluetoothDevice name"() {

        given:
        mockBluetoothDevice.name >> "testName"

        expect:
        rxBleDevice.getName() == "testName"
    }

    def "should return the BluetoothDevice address"() {

        given:
        mockBluetoothDevice.address >> "aa:aa:aa:aa:aa:aa"

        expect:
        rxBleDevice.getMacAddress() == "aa:aa:aa:aa:aa:aa"
    }

    def "equals() should return true when compared to a different RxBleDevice instance with the same underlying BluetoothDevice"() {

        given:
        def differentRxBleDeviceWithSameBluetoothDevice = new RxBleDeviceImpl(mockBluetoothDevice, null, BehaviorRelay.create())

        expect:
        rxBleDevice == differentRxBleDeviceWithSameBluetoothDevice
    }

    def "hashCode() should return the same value as a different RxBleDevice instance hashCode() with the same underlying BluetoothDevice"() {

        given:
        def differentRxBleDevice = new RxBleDeviceImpl(mockBluetoothDevice, null, BehaviorRelay.create())

        expect:
        rxBleDevice.hashCode() == differentRxBleDevice.hashCode()
    }

    @Unroll
    def "establishConnection() should call RxBleConnection.Connector.prepareConnection() => autoConnect:#autoConnectValue #establishConnectionCaller"() {

        given:
        ConnectionSetup connectionSetup = new ConnectionSetup.Builder()
            .setAutoConnect(autoConnectValue)
            .setSuppressIllegalOperationCheck(suppressIllegalOperationCheckValue)
            .build()
        def connectionObservable = establishConnectionCaller.connectionStartClosure.call(rxBleDevice, connectionSetup)

        when:
        connectionObservable.subscribe()

        then:
        1 * mockConnector.prepareConnection({ ConnectionSetup cs -> cs.autoConnect == autoConnectValue }) >> Observable.empty()

        where:
        [ autoConnectValue, suppressIllegalOperationCheckValue, establishConnectionCaller] << [
                [true, false],
                [true, false],
                establishConnectionCallers
        ].combinations()
    }

    @Unroll
    def "should emit only new states from BehaviourRelay<RxBleConnectionState>"() {

        given:
        connectionStateBehaviorRelay.call(initialState)
        rxBleDevice.observeConnectionStateChanges().subscribe(deviceConnectionStateSubscriber)

        when:
        connectionStateBehaviorRelay.call(initialState)

        then:
        deviceConnectionStateSubscriber.assertNoValues()

        when:
        connectionStateBehaviorRelay.call(nextState)

        then:
        deviceConnectionStateSubscriber.assertValue(nextState)

        where:
        initialState  | nextState
        DISCONNECTED  | CONNECTING
        DISCONNECTED  | CONNECTED
        DISCONNECTED  | DISCONNECTING
        CONNECTING    | CONNECTED
        CONNECTING    | DISCONNECTING
        CONNECTING    | DISCONNECTED
        CONNECTED     | CONNECTING
        CONNECTED     | DISCONNECTING
        CONNECTED     | DISCONNECTED
        DISCONNECTING | DISCONNECTED
        DISCONNECTING | CONNECTING
        DISCONNECTING | CONNECTED
    }

    @Unroll
    def "should emit connection and stay subscribed after it was established => call:#establishConnectionSetup"() {

        given:
        def testSubscriber = new TestSubscriber()
        def connectionObservable = establishConnectionSetup.establishConnection(rxBleDevice)

        when:
        connectionObservable.subscribe(testSubscriber)
        notifyConnectionWasEstablished()

        then:
        testSubscriber.assertSubscribed()
        testSubscriber.assertValueCount 1

        where:
        establishConnectionSetup << establishConnectionTestSetups
    }

    @Unroll
    def "should emit BleAlreadyConnectedException if already connected => firstCall:#establishConnectionSetup0 secondCall:#establishConnectionSetup1\""() {

        given:
        def testSubscriber = new TestSubscriber()
        def connectionObs0 = establishConnectionSetup0.establishConnection(rxBleDevice)
        def connectionObs1 = establishConnectionSetup1.establishConnection(rxBleDevice)
        connectionObs0.subscribe()
        notifyConnectionWasEstablished()

        when:
        connectionObs1.subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleAlreadyConnectedException

        where:
        [establishConnectionSetup0, establishConnectionSetup1] << [
                establishConnectionTestSetups,
                establishConnectionTestSetups
        ].combinations()
    }

    @Unroll
    def "should emit BleAlreadyConnectedException if there is already one subscriber to .establishConnection() => firstCall:#establishConnectionSetup0 secondCall:#establishConnectionSetup1"() {

        given:
        TestSubscriber testSubscriber = new TestSubscriber()
        def connectionObs0 = establishConnectionSetup0.establishConnection(rxBleDevice)
        def connectionObs1 = establishConnectionSetup1.establishConnection(rxBleDevice)
        connectionObs0.subscribe()

        when:
        connectionObs1.subscribe(testSubscriber)

        then:
        testSubscriber.assertError(BleAlreadyConnectedException)

        where:
        [establishConnectionSetup0, establishConnectionSetup1] << [
                establishConnectionTestSetups,
                establishConnectionTestSetups
        ].combinations()
    }

    @Unroll
    def "should create new connection if previous connection was established and released before second subscriber has subscribed => firstCall:#establishConnectionSetup0 secondCall:#establishConnectionSetup1"() {

        given:
        def firstSubscriber = new TestSubscriber()
        def secondSubscriber = new TestSubscriber()
        def connectionObs0 = establishConnectionSetup0.establishConnection(rxBleDevice)
        def connectionObs1 = establishConnectionSetup1.establishConnection(rxBleDevice)
        def subscription = connectionObs0.subscribe(firstSubscriber)
        notifyConnectionWasEstablished()
        subscription.unsubscribe()

        when:
        connectionObs1.subscribe(secondSubscriber)

        then:
        firstSubscriber.assertValueCount 1
        firstSubscriber.assertReceivedOnNextNot(secondSubscriber.onNextEvents)

        where:
        [establishConnectionSetup0, establishConnectionSetup1] << [
                establishConnectionTestSetups,
                establishConnectionTestSetups
        ].combinations()
    }

    @Unroll
    def "should not emit BleAlreadyConnectedException if there is already was subscriber to .establishConnection() but it unsubscribed => firstCall:#establishConnectionSetup0 secondCall:#establishConnectionSetup1"() {

        given:
        TestSubscriber testSubscriber = new TestSubscriber()
        def connectionObs0 = establishConnectionSetup0.establishConnection(rxBleDevice)
        def connectionObs1 = establishConnectionSetup1.establishConnection(rxBleDevice)
        connectionObs0.subscribe().unsubscribe()

        when:
        connectionObs1.subscribe(testSubscriber)

        then:
        testSubscriber.assertNoErrors()

        where:
        [establishConnectionSetup0, establishConnectionSetup1] << [
                establishConnectionTestSetups,
                establishConnectionTestSetups
        ].combinations()
    }

    @Unroll
    def "should unsubscribe from connection if it was dropped => call:#establishConnectionSetup"() {

        given:
        def connectionTestSubscriber = new TestSubscriber()
        establishConnectionSetup.establishConnection(rxBleDevice).subscribe(connectionTestSubscriber)
        notifyConnectionWasEstablished()

        when:
        dropConnection()

        then:
        connectionTestSubscriber.isUnsubscribed()

        where:
        establishConnectionSetup << establishConnectionTestSetups
    }

    @Unroll
    def "should return connection state from BehaviourRelay<RxBleConnectionState>"() {

        given:
        connectionStateBehaviorRelay.call(state)

        expect:
        rxBleDevice.getConnectionState() == state

        where:
        state << [DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING]
    }

    def "should return initial BluetoothDevice on getBluetoothDevice()"() {

        expect:
        rxBleDevice.getBluetoothDevice() == mockBluetoothDevice
    }

    public void startConnecting() {
        rxStartConnecting().subscribe({}, {})
    }

    public Observable<RxBleConnection> rxStartConnecting() {
        return rxBleDevice.establishConnection(false)
    }

    public void notifyConnectionWasEstablished() {
        mockConnectorEstablishConnectionPublishSubject.onNext(mockConnection)
    }

    public void dropConnection() {
        mockConnectorEstablishConnectionPublishSubject.onError(new BleGattException(mockBluetoothGatt, BleGattOperationType.CONNECTION_STATE))
    }

    private class EstablishConnectionCaller {

        final String description

        public final connectionStartClosure

        EstablishConnectionCaller(String description, Closure connectionStartClosure) {
            this.description = description
            this.connectionStartClosure = connectionStartClosure
        }

        @Override
        public String toString() {
            return "{" +
                    "method='" + description + '\'' +
                    '}';
        }
    }

    private class EstablishConnectionTestSetup {

        final EstablishConnectionCaller establishConnectionCaller;

        final ConnectionSetup connectionSetup;

        EstablishConnectionTestSetup(EstablishConnectionCaller establishConnectionCaller, ConnectionSetup connectionSetup) {
            this.establishConnectionCaller = establishConnectionCaller
            this.connectionSetup = connectionSetup
        }

        Observable<RxBleConnection> establishConnection(RxBleDevice d) {
            return establishConnectionCaller.connectionStartClosure.call(d, connectionSetup)
        }

        @Override
        public String toString() {
            return "{" +
                    "method=" + establishConnectionCaller.description +
                    ", autoConnect=" + connectionSetup.autoConnect +
                    ", suppressIllegalOperationCheck=" + connectionSetup.suppressOperationCheck +
                    '}';
        }
    }
}
