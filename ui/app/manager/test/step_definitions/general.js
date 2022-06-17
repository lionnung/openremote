const { join } = require('path');
const { Given, When, Then } = require("@cucumber/cucumber");

/**
 * Setup
 */

Given('Setup {string}', { timeout: 100000 }, async function (section) {

    await this.setup("smartcity", section)
})

Given('Setup {string} for map', { timeout: 100000 }, async function (section) {

    await this.setup("smartcity", section, "location")
})

Given('Setup {string} for rules', { timeout: 100000 }, async function (section) {

    await this.setup("smartcity", section, "config")
})

/**
 * General steps 
 */

/**
 * Login to a realm as expected user
 */
When('Login to OpenRemote {string} realm as {string}', { timeout: 10000 }, async function (realm, user) {
    let startTime = new Date() / 1000
    await this.openApp(realm)
    await this.login(user)
    this.logTime(startTime)
})

/**
 * Setting menu
 */
When('Navigate to {string} page', { timeout: 7000 }, async function (name) {
    let startTime = new Date() / 1000
    await this.wait(200)
    await this.navigateToMenuItem(name)
    await this.wait(200)
    this.logTime(startTime)
})

/**
 * Tab menu
 */
When('Navigate to {string} tab', { timeout: 7000 }, async function (tab) {
    let startTime = new Date() / 1000
    await this.navigateToTab(tab)
    await this.wait(200)
    this.logTime(startTime)
});

Then('Unselect', { timeout: 7000 }, async function () {
    let startTime = new Date() / 1000
    await this.unselectAll()
    this.logTime(startTime)
})

/**
 * snapshots
 */
Then('Snapshot {string}', async function (string) {
    const { page } = this;
    await page?.screenshot({ path: join('screenshots', `${string}.png`) });
});
