# Progress Report

Group Name: TheSeventhOne

## Members

| Name | SID      |
|------|----------|
| 赵奕沣  | 11811525 |
| 李文凯  | 11712704 |
| 何智刚  | 11713004 |
| 戴翔   | 11811205 |
| 李晗晓  | 11812511 |


## Chosen Issue

>There happen contact problems (like some teammates haven't fixed his own issue yet, as of report time, 
>or some of them just never tells his progress), so I will only tell the issue I am assigned to in this report. 
>And I will send related information later.

**#1724 in Jsoup**

XML Escape mode should escape > in attributes

### Reason for Choosing

It relates to encoding problems for security, which is easy to understand and fix.
Besides, ideal issues to be fixed in the future may have relation to it, which can be seen as a dependency.

### Test Scenario

1. Handle special character in parameter between quotation marks

   Like handling string parameter in normal programs, special characters can also appear in parameter fields in HTML. 

2. Escape html tags

   HTML tags may happen in parameter field, which is called "Nesting" for some purpose. 
   By escaping, it will be seen as normal text and makes no sense.

### Related Pull Request

[Link for pull request](https://github.com/jhy/jsoup/pull/1758)

### Code Standard

I have run Checkstyle, SpotBugs and PMD. There occur warnings in given level. 
But they cannot be prevented as following original code style does so in the repository, and I just follow it.

## Schedule

| Week    | Task           |
|---------|----------------|
| Week 12 | Anti-Spider    |
| Week 13 | SSRF knowledge |
| Week 14 | Authentication |

## Lab Session

Lab Session 2