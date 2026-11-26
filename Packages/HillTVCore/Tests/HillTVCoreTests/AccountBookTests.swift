import XCTest
@testable import HillTVCore

final class AccountBookTests: XCTestCase {
    private func account(_ user: String, _ server: String = "http://line.example.com:8080", password: String = "pw") -> XtreamAccount {
        XtreamAccount(serverURL: URL(string: server)!, username: user, password: password)
    }

    func testKeyIsTheOneTheStoresAlreadyUse() {
        XCTAssertEqual(AccountBook.key(account("viewer")), "http://line.example.com:8080|viewer")
    }

    func testMigratingKeepsTheOldLoginInUse() {
        let book = AccountBook.migrating(legacy: account("viewer"))
        XCTAssertEqual(book.accounts.map(\.username), ["viewer"])
        XCTAssertEqual(book.current?.username, "viewer")
        XCTAssertTrue(AccountBook.migrating(legacy: nil).isEmpty)
        XCTAssertNil(AccountBook.migrating(legacy: nil).current)
    }

    func testAddingSwitchesToTheNewAccount() {
        var book = AccountBook.migrating(legacy: account("viewer"))
        book.upsert(account("viewer2"))
        XCTAssertEqual(book.accounts.map(\.username), ["viewer", "viewer2"])
        XCTAssertEqual(book.current?.username, "viewer2")
    }

    func testSigningInAgainUpdatesThePasswordInPlace() {
        var book = AccountBook(accounts: [account("viewer"), account("viewer2")])
        book.upsert(account("viewer", password: "new"))
        XCTAssertEqual(book.accounts.map(\.username), ["viewer", "viewer2"])
        XCTAssertEqual(book.accounts[0].password, "new")
        XCTAssertEqual(book.current?.username, "viewer")
    }

    func testSelect() {
        var book = AccountBook(accounts: [account("a"), account("b")])
        XCTAssertEqual(book.current?.username, "a")
        XCTAssertTrue(book.select(AccountBook.key(account("b"))))
        XCTAssertEqual(book.current?.username, "b")
        XCTAssertFalse(book.select("nope"))
        XCTAssertEqual(book.current?.username, "b")
    }

    func testRemovingTheCurrentAccountMovesToTheFirstLeft() {
        var book = AccountBook(accounts: [account("a"), account("b"), account("c")], currentKey: AccountBook.key(account("b")))
        book.remove(AccountBook.key(account("b")))
        XCTAssertEqual(book.accounts.map(\.username), ["a", "c"])
        XCTAssertEqual(book.current?.username, "a")
        book.remove(AccountBook.key(account("c")))
        XCTAssertEqual(book.current?.username, "a")
        book.remove(AccountBook.key(account("a")))
        XCTAssertTrue(book.isEmpty)
        XCTAssertNil(book.currentKey)
    }

    func testAnEmptyBookStaysEmptyWhenSaved() throws {
        // Removing the last account saves an empty book, so the old login
        // item can't come back on the next launch.
        var book = AccountBook.migrating(legacy: account("viewer"))
        book.remove(AccountBook.key(account("viewer")))
        let read = try JSONDecoder().decode(AccountBook.self, from: JSONEncoder().encode(book))
        XCTAssertTrue(read.isEmpty)
    }

    func testRoundTripAndLenientReading() throws {
        let book = AccountBook(accounts: [account("a"), account("b", "http://other.example.net")], currentKey: AccountBook.key(account("b", "http://other.example.net")))
        let read = try JSONDecoder().decode(AccountBook.self, from: JSONEncoder().encode(book))
        XCTAssertEqual(read, book)
        // A current key that isn't saved falls back to the first account.
        let odd = #"{"accounts":[{"serverURL":"http://line.example.com","username":"a","password":"p"}],"currentKey":"gone"}"#
        XCTAssertEqual(try JSONDecoder().decode(AccountBook.self, from: Data(odd.utf8)).current?.username, "a")
    }

    func testDuplicatesInTheSavedListCollapse() {
        let book = AccountBook(accounts: [account("a"), account("a", password: "second")])
        XCTAssertEqual(book.accounts.count, 1)
        XCTAssertEqual(book.accounts[0].password, "second")
    }

    func testOthersOnTheSameServer() {
        let book = AccountBook(accounts: [
            account("a"),
            account("b", "http://LINE.example.com:8080/"),
            account("c", "http://other.example.net"),
            account("d"),
        ])
        XCTAssertEqual(book.others(onServerOf: account("a")).map(\.username), ["b", "d"])
        XCTAssertEqual(book.others(onServerOf: account("c", "http://other.example.net")).map(\.username), [])
    }

    func testLabelAndInitial() {
        XCTAssertEqual(account("viewer").label, "viewer · line.example.com")
        XCTAssertEqual(account("viewer").initial, "V")
        XCTAssertEqual(account("").initial, "?")
    }
}
