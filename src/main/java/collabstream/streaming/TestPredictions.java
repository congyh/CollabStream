package collabstream.streaming;

import java.io.FileReader;
import java.io.LineNumberReader;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DurationFormatUtils;

/**
 * 推荐结果评估程序
 *
 * <pre>
 * 对于训练样本非常大的情况, 通常使用留出法进行训练及调优即可; 当样本较少的情况下才需要进行k折交叉验证.
 *
 * 有以下几点需要注意的:
 * 1. 训练集和测试集是不相交的, 在训练集上对用户和物品模型进行训练, 然后在测试集上进行RMSE的计算才是正确的做法;
 * 2. (实际上是冷启动问题)在测试集中是可能出现训练集中未知的用户或者物品的, 对于此种情况的处理, 本文采用了以下应对方案:
 * 		- 如果至少用户和物品其中一个是已知的, 则用用户或物品历史评分评分(从训练集中读取)作为预测评分;
 * 		- 如果是完全未知的评分, 则使用训练集整体的历史评分均分作为预测评分;
 * 	这样做能够最大限度减少RMSE值.
 * </pre>
 */
public class TestPredictions {
	public static void main(String[] args) throws Exception {
		if (args.length < 7) {
			System.err.println("######## Wrong number of arguments");
			// 注: 这里提供的训练集只是为了计算训练集的平均评分和某用户和物品的历史评分均值, 作为测试时的预测评分使用.
			System.err.println("######## required args: numUsers numItems numLatent"
				+ " trainingFilename testFilename userFilename itemFilename");
			return;
		}
		
		long testStartTime = System.currentTimeMillis();
		System.out.printf("######## Testing started: %1$tY-%1$tb-%1$td %1$tT %tZ\n", testStartTime);

		/* 计算RMSE的时候需要提前知道用户数，物品数，隐向量的长度 */

		int numUsers = Integer.parseInt(args[0]);
		int numItems = Integer.parseInt(args[1]);
		int numLatent = Integer.parseInt(args[2]);
		String trainingFilename = args[3];
		String testFilename = args[4];
		String userFilename = args[5];
		String itemFilename = args[6];
		// 训练集的评分数值总和
		float trainingTotal = 0.0f;
		// 训练集的评分总个数
		int trainingCount = 0;
		// userCount是Map<userId, 评分个数>
		Map<Integer, Integer> userCount = new HashMap<Integer, Integer>();
		Map<Integer, Integer> itemCount = new HashMap<Integer, Integer>();
		// userTotal是Map<userId, 评分值总和>
		Map<Integer, Float> userTotal = new HashMap<Integer, Float>();
		Map<Integer, Float> itemTotal = new HashMap<Integer, Float>();
		
		long startTime = System.currentTimeMillis();
		System.out.printf("######## Started reading training file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", startTime);

		String line;

		/* 读入训练集 */

		LineNumberReader in = new LineNumberReader(new FileReader(trainingFilename));
		while ((line = in.readLine()) != null) {
			try {
				String[] token = StringUtils.split(line, ' ');
				// i是用户的编号
				int i = Integer.parseInt(token[0]);
				// j是物品的编号
				int j = Integer.parseInt(token[1]);
				float rating = Float.parseFloat(token[2]);
				
				trainingTotal += rating;
				++trainingCount;
				
				if (userCount.containsKey(i)) {
					userCount.put(i, userCount.get(i) + 1);
					userTotal.put(i, userTotal.get(i) + rating);
				} else {
					userCount.put(i, 1);
					userTotal.put(i, rating);
				}
				
				if (itemCount.containsKey(j)) {
					itemCount.put(j, itemCount.get(j) + 1);
					itemTotal.put(j, itemTotal.get(j) + rating);
				} else {
					itemCount.put(j, 1);
					itemTotal.put(j, rating);
				}
			} catch (Exception e) {
				System.err.printf("######## Could not parse line %d in %s\n%s\n", in.getLineNumber(), trainingFilename, e);
			}
		}
		in.close();
		// 训练集的平均评分
		float trainingAvg = trainingTotal / trainingCount;
		
		long endTime = System.currentTimeMillis();
		System.out.printf("######## Finished reading training file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", endTime);
		System.out.println("######## Time elapsed reading training file: "
			+ DurationFormatUtils.formatPeriod(startTime, endTime, "H:m:s") + " (h:m:s)");

		/* 初始化用户和物品矩阵 */

		float[][] userMatrix = new float[numUsers][numLatent];
		for (int i = 0; i < numUsers; ++i) {
			for (int k = 0; k < numLatent; ++k) {
				userMatrix[i][k] = 0.0f;
			}
		}
		
		float[][] itemMatrix = new float[numItems][numLatent];
		for (int i = 0; i < numItems; ++i) {
			for (int k = 0; k < numLatent; ++k) {
				itemMatrix[i][k] = 0.0f;
			}
		}

		/* 从文件中读入用户和物品矩阵 */

		startTime = System.currentTimeMillis();
		System.out.printf("######## Started reading user file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", startTime);
		
		in = new LineNumberReader(new FileReader(userFilename));
		while ((line = in.readLine()) != null) {
			try {
				String[] token = StringUtils.split(line, ' ');
				int i = Integer.parseInt(token[0]);
				for (int k = 0; k < numLatent; ++k) {
					userMatrix[i][k] = Float.parseFloat(token[k+1]);
				}
			} catch (Exception e) {
				System.err.printf("######## Could not parse line %d in %s\n%s\n", in.getLineNumber(), userFilename, e);
			}
		}
		in.close();
		
		endTime = System.currentTimeMillis();
		System.out.printf("######## Finished reading user file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", endTime);
		System.out.println("######## Time elapsed reading user file: "
			+ DurationFormatUtils.formatPeriod(startTime, endTime, "H:m:s") + " (h:m:s)");
		
		startTime = System.currentTimeMillis();
		System.out.printf("######## Started reading item file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", startTime);
		
		in = new LineNumberReader(new FileReader(itemFilename));
		while ((line = in.readLine()) != null) {
			try {
				String[] token = StringUtils.split(line, ' ');
				int j = Integer.parseInt(token[0]);
				for (int k = 0; k < numLatent; ++k) {
					itemMatrix[j][k] = Float.parseFloat(token[k+1]);
				}
			} catch (Exception e) {
				System.err.printf("######## Could not parse line %d in %s\n%s\n", in.getLineNumber(), itemFilename, e);
			}
		}
		in.close();
		
		endTime = System.currentTimeMillis();
		System.out.printf("######## Finished reading item file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", endTime);
		System.out.println("######## Time elapsed reading item file: "
			+ DurationFormatUtils.formatPeriod(startTime, endTime, "H:m:s") + " (h:m:s)");
		
		startTime = System.currentTimeMillis();
		System.out.printf("######## Started reading test file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", startTime);
		
		float totalSqErr = 0.0f; // 测试集误差平方和
		int numRatings = 0; // 测试集评分数量

		/* 从文件中读入测试集 */
		
		in = new LineNumberReader(new FileReader(testFilename));
		while ((line = in.readLine()) != null) {
			try {
				String[] token = StringUtils.split(line, ' ');
				// i是测试集中评分对应的userId
				int i = Integer.parseInt(token[0]);
				// j是测试集中评分对应的itemId
				int j = Integer.parseInt(token[1]);
				float rating = Float.parseFloat(token[2]);
				float prediction;
				
				boolean userKnown = userCount.containsKey(i);
				boolean itemKnown = itemCount.containsKey(j);

				/**
				 * 注意: 因为是用测试集进行RMSE计算的, 所以可能出现物品或者用户没有在训练集出现的情况,
				 * 所以才出现了如下的讨论的3种情况:
				 */

				// 1. 如果是已知用户对已知物品的评分, 则使用训练集得到的用户和物品隐向量来进行拟合
				if (userKnown && itemKnown) {
					prediction = 0.0f;
					for (int k = 0; k < numLatent; ++k) {
						prediction += userMatrix[i][k] * itemMatrix[j][k];
					}
					// 2. 如果是已知用户对未知物品或者未知用户对已知物品的评分,
					// 则使用该已知用户的历史平均评分或者已知物品的历史平均评分作为预测评分

				} else if (userKnown) {
					prediction = userTotal.get(i) / userCount.get(i);
				} else if (itemKnown) {
					prediction = itemTotal.get(j) / itemCount.get(j);
				} else { // 3. 如果评分完全是未知的, 则使用训练集的整体平均分作为预测评分
					prediction = trainingAvg;
				}
				// 计算预测值和实际评分值(测试集评分)的差异
				float diff = prediction - rating;
				totalSqErr += diff*diff;
				++numRatings;
			} catch (Exception e) {
				System.err.printf("######## Could not parse line %d in %s\n%s\n", in.getLineNumber(), testFilename, e);
			}
		}

		// RMSE计算
		double rmse = Math.sqrt(totalSqErr / numRatings);
		
		endTime = System.currentTimeMillis();
		System.out.printf("######## Finished reading test file: %1$tY-%1$tb-%1$td %1$tT %tZ\n", endTime);
		System.out.println("######## Time elapsed reading test file: "
			+ DurationFormatUtils.formatPeriod(startTime, endTime, "H:m:s") + " (h:m:s)");
		System.out.println("######## Total elapsed testing time: "
			+ DurationFormatUtils.formatPeriod(testStartTime, endTime, "H:m:s") + " (h:m:s)");
		System.out.println("######## Number of ratings used: " + numRatings);
		System.out.println("######## RMSE: " + rmse);
	}
}