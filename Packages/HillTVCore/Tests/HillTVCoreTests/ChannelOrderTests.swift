import XCTest
@testable import HillTVCore

final class ChannelOrderTests: XCTestCase {
    private func shown(_ order: ChannelOrder, _ provider: [Int], key: String = "12") -> [Int] {
        order.apply(to: provider, key: key) { $0 }
    }

    func testNoOrderLeavesTheListAlone() {
        let order = ChannelOrder()
        XCTAssertEqual(shown(order, [5, 6, 7]), [5, 6, 7])
        XCTAssertFalse(order.hasOrder(for: "12"))
        XCTAssertTrue(order.isEmpty)
    }

    func testMoveUpStoresOnlyTheTopOfTheList() {
        var order = ChannelOrder()
        let provider = Array(1...1000)
        // Channel 4 goes to the top.
        order.move(4, to: 0, in: provider, key: "12")
        XCTAssertEqual(Array(shown(order, provider).prefix(6)), [4, 1, 2, 3, 5, 6])
        XCTAssertEqual(order.lists["12"], [4, 1, 2, 3])
        XCTAssertTrue(order.hasOrder(for: "12"))
    }

    func testMoveDown() {
        var order = ChannelOrder()
        let provider = [1, 2, 3, 4, 5, 6]
        order.move(2, to: 4, in: provider, key: "12")
        XCTAssertEqual(shown(order, provider), [1, 3, 4, 5, 2, 6])
        XCTAssertEqual(order.lists["12"], [1, 3, 4, 5, 2])
    }

    func testSecondMoveKeepsTheFirst() {
        var order = ChannelOrder()
        let provider = Array(1...10)
        order.move(8, to: 0, in: provider, key: "12")
        XCTAssertEqual(shown(order, provider), [8, 1, 2, 3, 4, 5, 6, 7, 9, 10])
        // A small move near the top must not forget that 8 came from further down.
        order.move(1, to: 2, in: shown(order, provider), key: "12")
        XCTAssertEqual(shown(order, provider), [8, 2, 1, 3, 4, 5, 6, 7, 9, 10])
        order.move(10, to: 1, in: shown(order, provider), key: "12")
        XCTAssertEqual(shown(order, provider), [8, 10, 2, 1, 3, 4, 5, 6, 7, 9])
    }

    func testListsAreSeparate() {
        var order = ChannelOrder()
        order.move(3, to: 0, in: [1, 2, 3], key: "news")
        XCTAssertEqual(shown(order, [1, 2, 3], key: "news"), [3, 1, 2])
        XCTAssertEqual(shown(order, [1, 2, 3], key: "sports"), [1, 2, 3])
    }

    func testProviderAddsAndDropsChannels() {
        var order = ChannelOrder()
        order.move(3, to: 0, in: [1, 2, 3, 4], key: "12")
        // 2 is gone, 9 is new and listed first by the provider.
        XCTAssertEqual(shown(order, [9, 1, 3, 4]), [3, 1, 9, 4])
        // A list with none of the stored channels is shown as sent.
        XCTAssertEqual(shown(order, [20, 21]), [20, 21])
    }

    func testRepeatedChannelIsShownOnce() {
        var order = ChannelOrder()
        order.move(2, to: 0, in: [1, 2, 3], key: "12")
        XCTAssertEqual(shown(order, [1, 2, 2, 3]), [2, 1, 3])
    }

    func testMovesThatChangeNothing() {
        var order = ChannelOrder()
        order.move(2, to: 1, in: [1, 2, 3], key: "12")
        order.move(99, to: 0, in: [1, 2, 3], key: "12")
        order.move(1, to: 0, in: [], key: "12")
        XCTAssertTrue(order.isEmpty)
        // Past the end means the end.
        order.move(1, to: 50, in: [1, 2, 3], key: "12")
        XCTAssertEqual(shown(order, [1, 2, 3]), [2, 3, 1])
        order.move(1, to: -4, in: [2, 3, 1], key: "12")
        XCTAssertEqual(shown(order, [1, 2, 3]), [1, 2, 3])
    }

    func testReset() {
        var order = ChannelOrder()
        order.move(3, to: 0, in: [1, 2, 3], key: "12")
        order.reset("12")
        XCTAssertEqual(shown(order, [1, 2, 3]), [1, 2, 3])
        XCTAssertTrue(order.isEmpty)
    }

    func testSurvivesSavingAndLoading() throws {
        var order = ChannelOrder()
        order.move(3, to: 0, in: [1, 2, 3], key: "12")
        let data = try JSONEncoder().encode(order)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), #"{"12":[3,1,2]}"#)
        XCTAssertEqual(try JSONDecoder().decode(ChannelOrder.self, from: data), order)
        // Something else under the same key reads as "no order", not a failure.
        let odd = try JSONDecoder().decode(ChannelOrder.self, from: Data(#"["x"]"#.utf8))
        XCTAssertTrue(odd.isEmpty)
    }

    func testMovingHelper() {
        XCTAssertEqual(ChannelOrder.moving(["a", "b", "c", "d"], from: 3, to: 1), ["a", "d", "b", "c"])
        XCTAssertEqual(ChannelOrder.moving(["a", "b", "c", "d"], from: 0, to: 3), ["b", "c", "d", "a"])
        XCTAssertEqual(ChannelOrder.moving(["a", "b"], from: 7, to: 0), ["a", "b"])
        XCTAssertEqual(ChannelOrder.moving([Int](), from: 0, to: 0), [])
    }

    func testLiveStreamsKeepTheirOrder() {
        var order = ChannelOrder()
        let streams = [
            LiveStream(id: 1, name: "US | ABC"),
            LiveStream(id: 2, name: "US | CBS"),
            LiveStream(id: 3, name: "US | NBC"),
        ]
        order.move(3, to: 0, in: streams.map(\.id), key: "12")
        XCTAssertEqual(order.apply(to: streams, key: "12").map(\.name), ["US | NBC", "US | ABC", "US | CBS"])
    }
}
