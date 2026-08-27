# Java 基础 7 天补课计划（项目式）

## 为什么你现在“看得懂链路，写不出代码”

你缺的不是聪明，而是两个东西：

1. **Java 语法词汇量**：不知道 `record`、泛型、构造器、注解、接口分别解决什么问题。
2. **手写代码的肌肉记忆**：看代码像看地图，自己写像从零开车。

补课原则：**每个概念都要在 IDEA 里亲手写一遍，并且回到项目里找到它用过的地方。**

## 学习方法

每天三件事：

1. 上午：学语法概念，动手写 3 个小程序。
2. 下午：回到 Stage 1 项目，找到这个概念在项目里的用法，抄读对应文件。
3. 晚上：把当天概念用“自己的话”讲一遍，写进笔记。

在 IDEA 里建一个临时类练习，例如 `Learning.java`，放在 `rebuild/java-practice/` 下。
每天新建一个 `Day1.java`、`Day2.java`，不追求完整工程，只追求手写熟练。

## 7 天安排

### Day 1：变量、类型、运算符、流程控制

学：

- 基本类型：`int`、`long`、`boolean`、`String`
- 变量声明与赋值
- `if / else`、`for`、`while`

练：

- 写一个方法：密码长度小于 8 返回 false，否则 true。
- 写一个循环：打印 1 到 100 里所有偶数。
- 写一个方法：输入用户名，去空格后判断是否为空。

回项目看：

- `ChangePasswordRequest.java` 里的 `@Size(min = 8)` 为什么要 8
- `AuthService.changePassword` 里 `if (user == null)` 的判断

### Day 2：方法（参数、返回值、重载）

学：

- 方法的声明：访问修饰符、返回值、方法名、参数
- `return` 的时机
- 方法的职责：一个方法尽量只做一件事

练：

- 写一个 `boolean isValidPassword(String password)`。
- 写一个 `String normalizeUsername(String username)`。
- 写两个同名方法 `sum(int a, int b)` 和 `sum(int a, int b, int c)`。

回项目看：

- `AuthService.register` 方法为什么只做“注册”这一件事
- `blankToDefault` 这个私有方法为什么要单独抽出来

### Day 3：类、对象、构造器、this、getter/setter

学：

- `class` 是模板，`new` 是创建对象
- 字段、构造器、`this`
- getter/setter 为什么常用

练：

- 自己写一个 `User` 类，包含 `id`、`username`、`passwordHash`。
- 写构造器，创建两个 User 对象。
- 写 `getUsername()` 和 `setUsername()`。

回项目看：

- `User.java`：字段、getter/setter 和表字段的对应
- `UserView.from(user)`：为什么从实体转成视图对象

### Day 4：集合与泛型

学：

- `List`、`Map`、`Set`
- 泛型：`List<String>`、`Map<String, Long>`、`Result<T>`

练：

- 写 `List<User>`，添加 3 个用户，循环打印用户名。
- 写 `Map<String, Long>`，用户名到用户 ID 的映射。
- 写一个方法，从 `List<User>` 里按用户名找到用户。

回项目看：

- `Result<T>`：为什么 `T` 能装 `UserView`、`String`、`LoginResponse`
- `QueryWrapper<User>`：它本质是帮你拼 `where` 条件的工具

### Day 5：接口、继承、多态

学：

- `interface` 是约定，`implements` 是实现
- 继承：子类复用父类
- 多态：一个接口可以有多个实现

练：

- 写接口 `Greeter`，方法 `String greet(String name)`。
- 写 `ChineseGreeter` 和 `EnglishGreeter` 两个实现类。
- 写一个方法接收 `Greeter`，调用 `greet`。

回项目看：

- `UserMapper extends BaseMapper<User>`：为什么一行接口就有 `insert/selectById`
- `PasswordHasher` 是一个 `@Component`，它被注入到 `AuthService`

### Day 6：异常处理

学：

- `try / catch / finally`
- `throw` 与 `throws`
- 运行时异常 vs 检查异常
- 业务异常：用异常把错误信息传到上层

练：

- 写一个方法，输入负数就 `throw new IllegalArgumentException("不能为负数")`。
- 写 `try/catch` 捕获它并打印。
- 自定义一个 `MyBusinessException`，带错误码字段。

回项目看：

- `BusinessException`：错误码从哪里来
- `GlobalExceptionHandler`：为什么所有异常都从这里统一转成 JSON

### Day 7：注解、Record、依赖注入

学：

- 注解是给框架看的“标签”
- `record` 是简化版 DTO
- 依赖注入：Spring 负责创建对象并组装

练：

- 写一个 `record LoginRequest(String username, String password)`。
- 写一个类，构造器接收这个 record，并打印字段。
- 自己写一个注解 `@MyTag`，放在方法上，用反射读取它（可选）。

回项目看：

- `@PostMapping` 为什么是“门牌”
- `RegisterRequest` 为什么用 `record`
- `AuthController` 构造器里的 `AuthService` 是谁传入的

## 每天过关标准

- 小练习能独立写完，不翻资料。
- 能用中文解释“这个类/方法/注解在项目里是干什么的”。
- 当天最后一个小时能不看代码，画出当天概念和项目文件的对应关系。

## 补完后的下一步

补完这 7 天后，重新看一遍 Stage 1 的作业：

- 不翻讲义，独立实现 `PUT /api/me/password`。
- 不翻讲义，回答 STAGE-1 第 8 节的 5 道面试题。

能独立完成，再进入 Stage 2（MySQL + Flyway + 事务）。
